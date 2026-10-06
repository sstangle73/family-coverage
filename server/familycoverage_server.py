#!/usr/bin/env python3
"""Family Coverage server: an optional home for a household's recordings.

The phones copy their daily CSV files here, append-only: each upload says "file X from byte N", the server appends
what it doesn't have and answers with its size. It also answers the app's server test, serves the report page live
(behind a password) and a read-only export, and writes the same zips the phones export. Python standard library only.

    python familycoverage_server.py serve
    python familycoverage_server.py list                  # registered installs
    python familycoverage_server.py approve <device_id>   # let an install upload (check the id in its app first)
    python familycoverage_server.py revoke <device_id>
    python familycoverage_server.py set-report-password   # asks for the live report's password; --stdin reads it
    python familycoverage_server.py new-export-token      # writes the export token file; prints only its length
    python familycoverage_server.py export-zips <dir>     # one zip per install, in the app's export format

Settings (environment):
    FC_DATA            where the data lives (default /data)
    FC_LISTEN          host:port (default 0.0.0.0:8745)
    FC_HOUSEHOLD       if set, only installs from this household id may register
    FC_API_ALLOW       CIDRs that may use /api/* and /report/ (default: everyone; installs still need approving, and
                       the report its password)
    FC_OPEN_ALLOW      CIDRs that may read /healthz and /metrics (default: private networks)
    FC_EXPORT_TOKEN_FILE   the export API's token (default <FC_DATA>/export_token)
    FC_REPORT_PASSWORD_FILE  the report password's scrypt hash (default <FC_DATA>/report_password)
    FC_REPORT_HTML     the report page (default: report.html beside this file, else ../docs/report/index.html)
    FC_FCC_FILE        an FCC coverage layer for the report's map (default <FC_DATA>/fcc.json, if present; the format is
                       in docs/data-format.md)
    FC_TAILSCALE       the tailscale CLI, for the server test's path (default: tailscale). On a server that is itself a
                       Tailscale node, a phone that comes over Tailscale learns whether its path was direct or relayed.
"""
import base64
import getpass
import gzip
import hashlib
import hmac
import io
import ipaddress
import json
import os
import re
import secrets
import subprocess
import sys
import threading
import time
import zipfile
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

DATA = Path(os.environ.get("FC_DATA", "/data"))
LISTEN = os.environ.get("FC_LISTEN", "0.0.0.0:8745")
HOUSEHOLD = os.environ.get("FC_HOUSEHOLD", "").strip().lower()
API_ALLOW = os.environ.get("FC_API_ALLOW", "0.0.0.0/0,::/0")
OPEN_ALLOW = os.environ.get("FC_OPEN_ALLOW", "10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,127.0.0.0/8,::1/128,fc00::/7")
EXPORT_TOKEN_FILE = Path(os.environ.get("FC_EXPORT_TOKEN_FILE", str(DATA / "export_token")))
REPORT_PASSWORD_FILE = Path(os.environ.get("FC_REPORT_PASSWORD_FILE", str(DATA / "report_password")))
FCC_FILE = Path(os.environ.get("FC_FCC_FILE", str(DATA / "fcc.json")))
TAILSCALE = os.environ.get("FC_TAILSCALE", "tailscale")
HERE = Path(__file__).resolve().parent
REPORT_HTML = Path(os.environ.get("FC_REPORT_HTML") or next(
    (p for p in (HERE / "report.html", HERE.parent / "docs" / "report" / "index.html") if p.is_file()), HERE / "report.html"))
MAX_TEST_DOWN = 2_000_000
MAX_TEST_UP = 1_000_000
MAX_PENDING = 20
SESSION_HOURS = 12
SCRYPT = {"n": 2 ** 15, "r": 8, "p": 1, "maxmem": 64 * 1024 * 1024}

# The app's tables (its Tables.ALL).
NAME = re.compile(r"^(samples|track|tests|heartbeat|server|usage|checks|events|texts)-(\d{4}-\d{2}-\d{2})\.csv$")
DEVICE_ID = re.compile(r"^[0-9a-f]{16}$")
KEY = re.compile(r"^[0-9a-f]{64}$")
SLUG = re.compile(r"^[a-z0-9][a-z0-9-]{0,39}$")
MAX_BODY = 8 * 1024 * 1024        # compressed request size
MAX_INFLATED = 24 * 1024 * 1024   # after gunzip
MAX_MANIFEST = 256 * 1024

_lock = threading.Lock()
_hash_cache = {}  # path -> (size, mtime_ns, sha256)


def _nets(spec):
    return [ipaddress.ip_network(s.strip()) for s in spec.split(",") if s.strip()]


API_NETS, OPEN_NETS = _nets(API_ALLOW), _nets(OPEN_ALLOW)
# Tailscale's own addresses: a request from one of these came through the server's tailscaled.
TAILNET_NETS = _nets("100.64.0.0/10,fd7a:115c:a1e0::/48")


def tailnet_path(ip, status_json=None):
    """How this server's tailscaled reaches the peer with Tailscale address `ip` right now.

    path: direct (a UDP path, CurAddr set) | peer_relay | derp (active, no direct path) | idle | unknown.
    Tailscale often starts a flow on DERP and moves to direct within seconds, so the app asks at both ends of a test.
    """
    none = {"path": "unknown", "derp_region": "", "direct_family": "", "direct_lan": None}
    try:
        if status_json is None:
            out = subprocess.run([TAILSCALE, "status", "--json"], capture_output=True, timeout=8, check=True).stdout
            status_json = out.decode("utf-8", "replace")
        st = json.loads(status_json)
    except Exception as e:  # CLI missing, daemon down, bad JSON
        return {**none, "error": type(e).__name__}
    for p in (st.get("Peer") or {}).values():
        if ip not in (p.get("TailscaleIPs") or []):
            continue
        cur, relay, peer_relay = p.get("CurAddr") or "", p.get("Relay") or "", p.get("PeerRelay") or ""
        if cur:
            host = cur.rsplit(":", 1)[0].strip("[]")
            try:
                addr = ipaddress.ip_address(host)
                family, lan = ("ipv6" if addr.version == 6 else "ipv4"), addr.is_private
            except ValueError:
                family, lan = "", None
            return {"path": "direct", "derp_region": relay, "direct_family": family, "direct_lan": lan}
        if peer_relay:
            return {**none, "path": "peer_relay", "derp_region": relay}
        return {**none, "path": "derp" if p.get("Active") else "idle", "derp_region": relay}
    return none


def devices_file():
    return DATA / "devices.json"


def now_iso():
    return time.strftime("%Y-%m-%dT%H:%M:%S%z")


def slug(member):
    """A member's name as a folder name: lower-case letters and digits, hyphens between."""
    s = re.sub(r"[^a-z0-9]+", "-", member.lower()).strip("-")[:40].strip("-")
    return s or "member"


def clean_member(raw):
    if not isinstance(raw, str):
        return None
    s = " ".join(raw.split())
    if not s or len(s) > 30 or any(ord(ch) < 32 or ord(ch) == 127 for ch in s):
        return None
    return s


def hello(raw_path):
    """The app's server check: proof that this is a Family Coverage server, and whether it takes the household the
    app asks about ("ok"), takes any ("any"), or belongs to another one ("other"). It names no household itself."""
    query = raw_path.split("?", 1)[1] if "?" in raw_path else ""
    m = re.search(r"(?:^|&)household=([^&]*)", query)
    asked = m.group(1).lower() if m else ""
    if not HOUSEHOLD:
        household = "any"
    elif not asked:
        household = "locked"
    else:
        household = "ok" if asked == HOUSEHOLD else "other"
    return {"app": "family-coverage", "api": 1, "household": household}


def load_devices():
    try:
        return json.loads(devices_file().read_text(encoding="utf-8"))
    except FileNotFoundError:
        return {}


def save_devices(devs):
    DATA.mkdir(parents=True, exist_ok=True)
    tmp = devices_file().with_suffix(".tmp")
    tmp.write_text(json.dumps(devs, indent=2, sort_keys=True), encoding="utf-8")
    os.replace(tmp, devices_file())


def key_hash(key):
    return hashlib.sha256(key.encode("ascii")).hexdigest()


def file_sha256(path):
    st = path.stat()
    cached = _hash_cache.get(str(path))
    if cached and cached[0] == st.st_size and cached[1] == st.st_mtime_ns:
        return cached[2]
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    digest = h.hexdigest()
    _hash_cache[str(path)] = (st.st_size, st.st_mtime_ns, digest)
    return digest


def device_dirs():
    """(member slug, device id, path) for every install that has uploaded anything."""
    if not DATA.is_dir():
        return []
    out = []
    for member_dir in sorted(p for p in DATA.iterdir() if p.is_dir() and SLUG.match(p.name)):
        for dev_dir in sorted(p for p in member_dir.iterdir() if p.is_dir() and DEVICE_ID.match(p.name)):
            out.append((member_dir.name, dev_dir.name, dev_dir))
    return out


def export_listing():
    files = []
    for member, device_id, dev_dir in device_dirs():
        for f in sorted(dev_dir.iterdir()):
            m = NAME.match(f.name)
            if not m:
                continue
            st = f.stat()
            files.append({
                "path": f"{member}/{device_id}/{f.name}",
                "member": member,
                "device_id": device_id,
                "table": m.group(1),
                "date": m.group(2),
                "size": st.st_size,
                "sha256": file_sha256(f),
                "mtime": int(st.st_mtime),
            })
    return {"append_only": True, "files": files}


def complete_rows(path):
    """The file up to its last newline: a row still arriving waits for the next export."""
    data = path.read_bytes()
    cut = data.rfind(b"\n")
    return data[:cut + 1] if cut >= 0 else b""


def device_manifest(member, device_id, dev_dir, devs):
    """The phone's own manifest (household, places) if it sent one, else what the server knows."""
    try:
        manifest = json.loads((dev_dir / "manifest.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        manifest = {"format": "family-coverage-export", "version": 1,
                    "member": devs.get(device_id, {}).get("member", member), "device_id": device_id}
    manifest["device_id"] = device_id
    return manifest


def write_device_zip(member, device_id, dev_dir, fh, devs):
    """One install's data, laid out like the app's own export, so the report page opens either."""
    csvs = sorted(f for f in dev_dir.iterdir() if NAME.match(f.name))
    manifest = device_manifest(member, device_id, dev_dir, devs)
    manifest["files"] = [f"csv/{f.name}" for f in csvs]
    manifest["exported_at"] = datetime.now().astimezone().isoformat(timespec="milliseconds")
    manifest["exported_by"] = "server"
    with zipfile.ZipFile(fh, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2))
        for f in csvs:
            z.writestr(f"csv/{f.name}", complete_rows(f))
    return len(csvs)


def export_zips(out_dir):
    """One zip per install into out_dir, for the report page."""
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    devs = load_devices()
    written = []
    for member, device_id, dev_dir in device_dirs():
        if not any(NAME.match(f.name) for f in dev_dir.iterdir()):
            continue
        target = out / f"family-coverage-{member}-{device_id[:6]}-{datetime.now().date()}.zip"
        with open(target, "wb") as fh:
            write_device_zip(member, device_id, dev_dir, fh, devs)
        written.append(target)
    return written


# ---- the live report: the page, its password, and each phone's data as an export zip ---------------------------

def hash_password(pw):
    salt = os.urandom(16)
    dk = hashlib.scrypt(pw.encode("utf-8"), salt=salt, dklen=32, **SCRYPT)
    return "scrypt$%d$%d$%d$%s$%s" % (SCRYPT["n"], SCRYPT["r"], SCRYPT["p"], base64.b64encode(salt).decode(),
                                      base64.b64encode(dk).decode())


def check_password(pw):
    try:
        kind, n, r, p, salt, want = REPORT_PASSWORD_FILE.read_text(encoding="utf-8").strip().split("$")
        want = base64.b64decode(want)
        dk = hashlib.scrypt(pw.encode("utf-8"), salt=base64.b64decode(salt), n=int(n), r=int(r), p=int(p),
                            maxmem=SCRYPT["maxmem"], dklen=len(want))
        return kind == "scrypt" and hmac.compare_digest(dk, want)
    except (OSError, ValueError):
        return False


def write_password_hash(pw):
    if len(pw) < 12:
        raise ValueError("the password must be at least 12 characters")
    REPORT_PASSWORD_FILE.parent.mkdir(parents=True, exist_ok=True)
    tmp = REPORT_PASSWORD_FILE.with_suffix(".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w", encoding="utf-8") as f:
        f.write(hash_password(pw) + "\n")
    os.replace(tmp, REPORT_PASSWORD_FILE)


SESSIONS, FAILS, _session_lock = {}, [], threading.Lock()


def session_new():
    tok = secrets.token_urlsafe(32)
    now = time.time()
    exp = now + SESSION_HOURS * 3600
    with _session_lock:
        for t in [t for t, e in SESSIONS.items() if e < now]:
            del SESSIONS[t]
        SESSIONS[tok] = exp
    return tok, exp


def session_ok(tok):
    with _session_lock:
        exp = SESSIONS.get(tok)
        if exp and exp > time.time():
            return True
        SESSIONS.pop(tok, None)
    return False


def report_page():
    """The report page, switched to live mode: it asks this server for the data instead of files."""
    html = REPORT_HTML.read_text(encoding="utf-8")
    live = '<script>window.FC_LIVE_API = "/api/report";</script>'
    return re.sub(r"<!--FC_LIVE[^>]*-->", lambda _: live, html, count=1).encode("utf-8")


def device_version(dev_dir):
    """Changes whenever the install's files do, so the page fetches a phone again only after it uploads."""
    h = hashlib.sha1()
    for f in sorted(dev_dir.iterdir()):
        if NAME.match(f.name) or f.name == "manifest.json":
            st = f.stat()
            h.update(f"{f.name}:{st.st_size}:{st.st_mtime_ns};".encode())
    return h.hexdigest()[:16]


def report_devices():
    devs = load_devices()
    out = []
    for member, device_id, dev_dir in device_dirs():
        d = devs.get(device_id, {})
        files = [f for f in dev_dir.iterdir() if NAME.match(f.name)]
        if not files:
            continue
        out.append({"id": device_id, "member": d.get("member", member), "version": device_version(dev_dir),
                    "files": len(files), "bytes": sum(f.stat().st_size for f in files),
                    "last_upload_epoch": d.get("last_upload_epoch")})
    return {"devices": out}


def report_zip(device_id):
    for member, did, dev_dir in device_dirs():
        if did == device_id:
            buf = io.BytesIO()
            write_device_zip(member, did, dev_dir, buf, load_devices())
            return buf.getvalue()
    return None


def last_heartbeat(dev_dir):
    """(timestamp, state) of the newest heartbeat row in one install's files."""
    best = None
    for f in dev_dir.glob("heartbeat-*.csv"):
        try:
            with open(f, "rb") as fh:
                fh.seek(max(0, f.stat().st_size - 512))
                tail = fh.read().decode("utf-8", "replace").strip().splitlines()
        except OSError:
            continue
        if not tail:
            continue
        cols = tail[-1].split(",")
        if len(cols) < 3 or cols[0] == "ts":
            continue
        try:
            ts = datetime.fromisoformat(cols[0]).timestamp()
        except ValueError:
            continue
        if best is None or ts > best[0]:
            best = (ts, cols[2])
    return best


def metrics_text():
    devs = load_devices()
    lines = ["# HELP familycoverage_last_upload_timestamp_seconds Last upload accepted from each member.",
             "# TYPE familycoverage_last_upload_timestamp_seconds gauge"]
    last_up, status_count = {}, {}
    for d in devs.values():
        st = d.get("status", "pending")
        status_count[st] = status_count.get(st, 0) + 1
        if st == "approved" and d.get("last_upload_epoch"):
            m = slug(d.get("member", "member"))
            last_up[m] = max(last_up.get(m, 0), d["last_upload_epoch"])
    for member, ts in sorted(last_up.items()):
        lines.append(f'familycoverage_last_upload_timestamp_seconds{{member="{member}"}} {ts}')
    lines += ["# HELP familycoverage_last_heartbeat_timestamp_seconds Newest heartbeat row each member has uploaded.",
              "# TYPE familycoverage_last_heartbeat_timestamp_seconds gauge"]
    beats = {}
    for member, _, dev_dir in device_dirs():
        hb = last_heartbeat(dev_dir)
        if hb and (member not in beats or hb[0] > beats[member][0]):
            beats[member] = hb
    for member, (ts, _) in sorted(beats.items()):
        lines.append(f'familycoverage_last_heartbeat_timestamp_seconds{{member="{member}"}} {int(ts)}')
    lines += ["# HELP familycoverage_ended 1 when the member's last heartbeat said recording ended.",
              "# TYPE familycoverage_ended gauge"]
    for member, (_, state) in sorted(beats.items()):
        lines.append(f'familycoverage_ended{{member="{member}"}} {1 if state == "ENDED" else 0}')
    lines += ["# HELP familycoverage_devices Registered installs by status.", "# TYPE familycoverage_devices gauge"]
    for status, n in sorted(status_count.items()):
        lines.append(f'familycoverage_devices{{status="{status}"}} {n}')
    lines += ["# HELP familycoverage_stored_bytes Bytes stored per member and table.",
              "# TYPE familycoverage_stored_bytes gauge"]
    totals = {}
    for member, _, dev_dir in device_dirs():
        for f in dev_dir.iterdir():
            m = NAME.match(f.name)
            if m:
                k = (member, m.group(1))
                totals[k] = totals.get(k, 0) + f.stat().st_size
    for (member, table), n in sorted(totals.items()):
        lines.append(f'familycoverage_stored_bytes{{member="{member}",table="{table}"}} {n}')
    return "\n".join(lines) + "\n"


class Handler(BaseHTTPRequestHandler):
    server_version = "familycoverage/1"
    protocol_version = "HTTP/1.1"

    def _client_ip(self):
        ip = ipaddress.ip_address(self.client_address[0])
        return ip.ipv4_mapped if ip.version == 6 and ip.ipv4_mapped else ip

    def _allowed(self, nets):
        ip = self._client_ip()
        return any(ip in n for n in nets)

    def _test_path(self):
        """The server test's path: through Tailscale, whether tailscaled has a direct path to this phone or relays."""
        ip = self._client_ip()
        if not any(ip in n for n in TAILNET_NETS):
            return {"path": "not_tailscale", "derp_region": "", "direct_family": "", "direct_lan": None}
        return tailnet_path(str(ip))

    def _send(self, code, body, ctype="application/json", extra=None):
        data = body if isinstance(body, bytes) else json.dumps(body).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def _bearer(self):
        auth = self.headers.get("Authorization", "")
        return auth[7:].strip() if auth.startswith("Bearer ") else ""

    def log_message(self, fmt, *args):  # one line per request, never a key
        sys.stderr.write("%s %s %s\n" % (time.strftime("%Y-%m-%dT%H:%M:%S"), self.client_address[0], fmt % args))

    def _approved_device(self):
        """(device id, device) behind this request's key, or None after sending the error."""
        key = self._bearer()
        if not KEY.match(key):
            self._send(401, {"error": "no key"})
            return None
        kh = key_hash(key)
        device_id = kh[:16]
        d = load_devices().get(device_id)
        if d is None or not hmac.compare_digest(d.get("key_sha256", ""), kh):
            self._send(401, {"error": "unknown device"})
            return None
        if d.get("status") != "approved":
            self._send(403, {"status": d.get("status", "pending")})
            return None
        return device_id, d

    def _drain(self, limit):
        """Reads (and drops) a request body before answering, so keep-alive stays in step. Returns its size."""
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return -1
        if length < 0 or length > limit:
            return -1
        received = 0
        while received < length:
            chunk = self.rfile.read(min(65536, length - received))
            if not chunk:
                break
            received += len(chunk)
        return received

    # ---- GET ---------------------------------------------------------------------------------------------------
    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/":
            return self._send(302, b"", "text/plain", {"Location": "/report/"})
        if path == "/healthz":
            if not self._allowed(OPEN_NETS):
                return self._send(403, {"error": "forbidden"})
            try:
                DATA.mkdir(parents=True, exist_ok=True)
                return self._send(200, {"ok": True, "devices": len(load_devices())})
            except Exception as e:  # unreadable data dir or devices.json
                return self._send(503, {"ok": False, "error": type(e).__name__})
        if path == "/metrics":
            if not self._allowed(OPEN_NETS):
                return self._send(403, {"error": "forbidden"})
            return self._send(200, metrics_text().encode("utf-8"), "text/plain; version=0.0.4")
        if not self._allowed(API_NETS):
            return self._send(403, {"error": "forbidden"})
        if path == "/report":
            return self._send(301, b"", "text/plain", {"Location": "/report/"})
        if path == "/report/":
            try:
                return self._send(200, report_page(), "text/html; charset=utf-8")
            except OSError:
                return self._send(503, {"error": "the report page isn't installed beside the server"})
        if path == "/api/hello":
            return self._send(200, hello(self.path))
        if path.startswith("/api/report/"):
            return self._report_get(path)
        if path == "/api/export" or path.startswith("/api/export/"):
            return self._export(path)
        if path == "/api/test/ping":
            if self._approved_device() is None:
                return None
            return self._send(200, b"ok", "text/plain")
        if path == "/api/test/down":
            if self._approved_device() is None:
                return None
            m = re.search(r"(?:^|&)bytes=(\d+)", self.path.split("?", 1)[1] if "?" in self.path else "")
            n = min(int(m.group(1)) if m else 0, MAX_TEST_DOWN)
            return self._send(200, bytes(n), "application/octet-stream")
        return self._send(404, {"error": "not found"})

    def _report_get(self, path):
        if not session_ok(self._bearer()):
            return self._send(401, {"error": "enter the report password"})
        if path == "/api/report/devices":
            return self._send(200, report_devices())
        if path == "/api/report/fcc":
            # The carriers' claimed coverage for the map, if the household put a layer here. Browsers can't fetch the
            # FCC's own data from another site, so the server hands it over.
            if not FCC_FILE.is_file():
                return self._send(404, {"error": "no FCC layer on this server"})
            return self._send(200, FCC_FILE.read_bytes())
        m = re.match(r"^/api/report/zip/([0-9a-f]{16})$", path)
        if m:
            data = report_zip(m.group(1))
            if data is None:
                return self._send(404, {"error": "no such phone"})
            return self._send(200, data, "application/zip")
        return self._send(404, {"error": "not found"})

    def _report_login(self, body):
        if not REPORT_PASSWORD_FILE.is_file():
            return self._send(503, {"error": "no report password yet: run set-report-password on the server"})
        now = time.time()
        with _session_lock:
            FAILS[:] = [t for t in FAILS if now - t < 900]
            if len(FAILS) >= 5:
                return self._send(429, {"error": "too many wrong passwords: wait 15 minutes"})
        if not isinstance(body.get("password"), str) or not check_password(body["password"]):
            with _session_lock:
                FAILS.append(now)
            self.log_message("report login refused")
            return self._send(401, {"error": "wrong password"})
        tok, exp = session_new()
        self.log_message("report unlocked for %d h", SESSION_HOURS)
        return self._send(200, {"token": tok, "expires": int(exp)})

    def _export(self, path):
        try:
            token = EXPORT_TOKEN_FILE.read_text(encoding="utf-8").strip()
        except OSError:
            return self._send(503, {"error": "no export token configured (run new-export-token)"})
        if not token or not hmac.compare_digest(self._bearer(), token):
            return self._send(401, {"error": "bad export token"})
        rest = path[len("/api/export"):].strip("/")
        if not rest:
            return self._send(200, export_listing())
        parts = rest.split("/")
        if len(parts) != 3 or not SLUG.match(parts[0]) or not DEVICE_ID.match(parts[1]) or not NAME.match(parts[2]):
            return self._send(404, {"error": "no such file"})
        f = DATA / parts[0] / parts[1] / parts[2]
        if not f.is_file():
            return self._send(404, {"error": "no such file"})
        size = f.stat().st_size
        start = 0
        m = re.match(r"^bytes=(\d+)-$", self.headers.get("Range", ""))
        if m:
            start = int(m.group(1))
            if start > size:
                return self._send(416, {"error": "range beyond end"}, extra={"Content-Range": f"bytes */{size}"})
        with open(f, "rb") as fh:
            fh.seek(start)
            data = fh.read(size - start)
        extra = {"Accept-Ranges": "bytes", "X-Sha256": file_sha256(f)}
        if m:
            extra["Content-Range"] = f"bytes {start}-{max(start, size - 1)}/{size}"
            return self._send(206, data, "text/csv; charset=utf-8", extra)
        return self._send(200, data, "text/csv; charset=utf-8", extra)

    # ---- POST --------------------------------------------------------------------------------------------------
    def do_POST(self):
        path = self.path.split("?", 1)[0]
        if not self._allowed(API_NETS):
            self._drain(MAX_BODY)
            return self._send(403, {"error": "forbidden"})
        if path == "/api/test/up":
            received = self._drain(MAX_TEST_UP)
            if received < 0:
                return self._send(413, {"error": "body too large"})
            if self._approved_device() is None:
                return None
            return self._send(200, {"bytes": received})
        if path in ("/api/test/begin", "/api/test/end"):
            if self._drain(MAX_TEST_UP) < 0:
                return self._send(413, {"error": "body too large"})
            if self._approved_device() is None:
                return None
            return self._send(200, self._test_path())
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return self._send(400, {"error": "bad length"})
        if length <= 0 or length > MAX_BODY:
            return self._send(413, {"error": "body too large or empty"})
        raw = self.rfile.read(length)
        if self.headers.get("Content-Encoding", "").lower() == "gzip":
            try:
                raw = gzip.GzipFile(fileobj=io.BytesIO(raw)).read(MAX_INFLATED + 1)
            except OSError:
                return self._send(400, {"error": "bad gzip"})
            if len(raw) > MAX_INFLATED:
                return self._send(413, {"error": "too large"})
        try:
            body = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            return self._send(400, {"error": "bad json"})
        if not isinstance(body, dict):
            return self._send(400, {"error": "bad json"})
        if path == "/api/report/login":
            return self._report_login(body)
        if path == "/api/register":
            return self._register(body)
        if path == "/api/upload":
            return self._upload(body)
        if path == "/api/manifest":
            return self._manifest(body, len(raw))
        return self._send(404, {"error": "not found"})

    def _register(self, body):
        member, key = clean_member(body.get("member")), body.get("key", "")
        household = str(body.get("household", "")).lower()
        if member is None or not KEY.match(key or ""):
            return self._send(400, {"error": "bad member or key"})
        if HOUSEHOLD and household != HOUSEHOLD:
            return self._send(403, {"error": "this server belongs to another household"})
        kh = key_hash(key)
        device_id = kh[:16]
        with _lock:
            devs = load_devices()
            d = devs.get(device_id)
            if d is None:
                pending = sum(1 for x in devs.values() if x.get("status") == "pending")
                if pending >= MAX_PENDING:
                    return self._send(429, {"error": "too many installs waiting for approval"})
                d = {"member": member, "key_sha256": kh, "status": "pending", "first_seen": now_iso()}
                devs[device_id] = d
            elif not hmac.compare_digest(d["key_sha256"], kh):
                return self._send(409, {"error": "device id collision"})
            d.update({
                "household": household[:16],
                "model": str(body.get("model", ""))[:80],
                "app_version": str(body.get("app_version", ""))[:40],
                "consent_at": str(body.get("consent_at", ""))[:40],
                "last_seen": now_iso(),
            })
            save_devices(devs)
        self.log_message("register %s %s -> %s", device_id, slug(member), d["status"])
        return self._send(200, {"device_id": device_id, "status": d["status"]})

    def _upload(self, body):
        found = self._approved_device()
        if found is None:
            return None
        device_id, d = found
        files = body.get("files")
        if not isinstance(files, list) or len(files) > 16:
            return self._send(400, {"error": "bad files"})
        member_dir = slug(d.get("member", "member"))
        out = []
        with _lock:
            for entry in files:
                if not isinstance(entry, dict):
                    return self._send(400, {"error": "bad file entry"})
                name, offset, data = entry.get("name", ""), entry.get("offset"), entry.get("data", "")
                if not isinstance(name, str) or not NAME.match(name) or not isinstance(offset, int) or offset < 0 \
                        or not isinstance(data, str):
                    return self._send(400, {"error": "bad file entry"})
                blob = data.encode("utf-8")
                f = DATA / member_dir / device_id / name
                f.parent.mkdir(parents=True, exist_ok=True)
                size = f.stat().st_size if f.exists() else 0
                if offset > size:
                    out.append({"name": name, "size": size, "error": "gap"})
                    continue
                skip = size - offset
                if skip < len(blob):
                    with open(f, "ab") as fh:
                        fh.write(blob[skip:])
                    size = f.stat().st_size
                out.append({"name": name, "size": size})
            devs = load_devices()
            devs[device_id]["last_seen"] = now_iso()
            devs[device_id]["last_upload_epoch"] = int(time.time())
            ua = self.headers.get("User-Agent", "")
            if ua.startswith("FamilyCoverage/"):
                devs[device_id]["app_version"] = ua.split("/", 1)[1][:40]
            save_devices(devs)
        return self._send(200, {"status": "approved", "files": out})

    def _manifest(self, body, size):
        found = self._approved_device()
        if found is None:
            return None
        device_id, d = found
        if size > MAX_MANIFEST or body.get("format") != "family-coverage-export":
            return self._send(400, {"error": "bad manifest"})
        f = DATA / slug(d.get("member", "member")) / device_id / "manifest.json"
        f.parent.mkdir(parents=True, exist_ok=True)
        tmp = f.with_suffix(".tmp")
        tmp.write_text(json.dumps(body, indent=2), encoding="utf-8")
        os.replace(tmp, f)
        return self._send(200, {"ok": True})


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        # A phone walking off the Wi-Fi mid-request drops the connection: one line, not a traceback.
        exc = sys.exc_info()[1]
        if isinstance(exc, (ConnectionResetError, BrokenPipeError, TimeoutError)):
            sys.stderr.write("%s %s connection dropped (%s)\n"
                             % (time.strftime("%Y-%m-%dT%H:%M:%S"), client_address[0], type(exc).__name__))
            return
        super().handle_error(request, client_address)


def cli(argv):
    cmd = argv[1] if len(argv) > 1 else "serve"
    if cmd == "serve":
        host, port = LISTEN.rsplit(":", 1)
        DATA.mkdir(parents=True, exist_ok=True)
        httpd = Server((host.strip("[]"), int(port)), Handler)
        sys.stderr.write(f"familycoverage: listening on {LISTEN}, data {DATA}\n")
        httpd.serve_forever()
    elif cmd == "list":
        for did, d in sorted(load_devices().items(), key=lambda kv: kv[1].get("first_seen", "")):
            print(f"{did}  {d.get('status', '?'):9} {d.get('member', '?'):12} {d.get('model', ''):24} "
                  f"v{d.get('app_version', '')}  consent {d.get('consent_at', '') or '-'}  last {d.get('last_seen', '')}")
    elif cmd in ("approve", "revoke") and len(argv) == 3:
        with _lock:
            devs = load_devices()
            if argv[2] not in devs:
                sys.exit(f"no device {argv[2]}")
            devs[argv[2]]["status"] = "approved" if cmd == "approve" else "revoked"
            devs[argv[2]][cmd + "d_at"] = now_iso()
            save_devices(devs)
        print(f"{argv[2]} {devs[argv[2]]['status']}")
    elif cmd == "set-report-password":
        if "--stdin" in argv:
            pw = sys.stdin.readline().rstrip("\r\n")   # PowerShell pipes CRLF
        else:
            pw = getpass.getpass("Report password (12 characters or more): ")
            if getpass.getpass("Again: ") != pw:
                sys.exit("FAILED: the two didn't match")
        try:
            write_password_hash(pw)
        except ValueError as e:
            sys.exit(f"FAILED: {e}")
        pw = None
        print(f"report password: hash written to {REPORT_PASSWORD_FILE} (mode 600)")
    elif cmd == "new-export-token":
        EXPORT_TOKEN_FILE.parent.mkdir(parents=True, exist_ok=True)
        token = secrets.token_hex(32)
        tmp = EXPORT_TOKEN_FILE.with_suffix(".tmp")
        tmp.write_text(token + "\n", encoding="utf-8")
        os.chmod(tmp, 0o600)
        os.replace(tmp, EXPORT_TOKEN_FILE)
        print(f"export token written to {EXPORT_TOKEN_FILE} ({len(token)} characters)")
    elif cmd == "export-zips" and len(argv) == 3:
        for p in export_zips(argv[2]):
            print(p)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    cli(sys.argv)
