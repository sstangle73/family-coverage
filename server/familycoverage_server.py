#!/usr/bin/env python3
"""Family Coverage server: an optional home for a household's recordings.

The phones copy their daily CSV files here, append-only: each upload says "file X from byte N", the server appends
what it doesn't have and answers with its size. It also answers the app's server test, serves the report page live
(behind a password) and a read-only export, and writes the same zips the phones export. Python standard library only,
except the optional FCC comparison, which also needs the h3 and requests packages.

    python familycoverage_server.py serve
    python familycoverage_server.py list                  # registered installs
    python familycoverage_server.py approve <device_id>   # let an install upload (check the id in its app first)
    python familycoverage_server.py revoke <device_id>
    python familycoverage_server.py set-report-password   # asks for the live report's password; --stdin reads it
    python familycoverage_server.py new-export-token      # writes the export token file; prints only its length
    python familycoverage_server.py export-zips <dir>     # one zip per install, in the app's export format
    python familycoverage_server.py build-fcc             # builds the FCC layer (fcc.json) once, e.g. from cron

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
                       in docs/data-format.md): hand-made, or built by the server (FC_FCC, build-fcc)
    FC_FCC             auto: build FC_FCC_FILE about a minute after startup, then every 24 hours, from the FCC's files
                       for the states the phones have readings in (default: off). Needs h3 and requests.
    FC_FCC_STATES      only these states' files, comma-separated abbreviations or FIPS codes, e.g. MA,VT (default: every
                       state with readings)
    FC_FCC_CACHE       where the downloaded files are kept (default <FC_DATA>/fcc-cache)
    FC_TAILSCALE       the tailscale CLI, for the server test's path (default: tailscale). On a server that is itself a
                       Tailscale node, a phone that comes over Tailscale learns whether its path was direct or relayed.
"""
import base64
import csv
import getpass
import gzip
import hashlib
import hmac
import io
import ipaddress
import json
import math
import os
import re
import secrets
import struct
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import zipfile
import zlib
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
FCC_MODE = os.environ.get("FC_FCC", "").strip().lower()
FCC_STATES = os.environ.get("FC_FCC_STATES", "")
FCC_CACHE = Path(os.environ.get("FC_FCC_CACHE", str(DATA / "fcc-cache")))
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


def log(msg):
    sys.stderr.write("%s %s\n" % (time.strftime("%Y-%m-%dT%H:%M:%S"), msg))


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
    return "\n".join(lines + fcc_metrics()) + "\n"


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


# ---- the FCC comparison: what the carriers claim, where the family has readings ---------------------------------
#
# Opt-in (FC_FCC=auto, or build-fcc): the server makes fcc.json itself, from the National Broadband Map's per-state
# files of what Verizon, AT&T and T-Mobile claim to the FCC, cut down to the hexagons the phones have readings in.
# Privacy: only whole-state files are downloaded, so the family's positions never leave the server. Never use the
# map's point lookups (mobile/detail/..., h3Index/...): each one would tell the FCC a place the family went. Downloads
# go only to broadbandmap.fcc.gov and www2.census.gov (FCC_HOSTS). This part alone needs packages beyond the standard
# library, h3 and requests, and imports them only when it runs. requests because the FCC's site turns Python's own
# urllib away; it's an ordinary client here, saying who it is, and never dressed up as a browser.

FCC_API = "https://broadbandmap.fcc.gov/nbm/map/api/"
FCC_HOSTS = ("broadbandmap.fcc.gov", "www2.census.gov")
# The Census Bureau's cartographic state outlines (1:20 million): the newest year, else the one before.
FCC_STATE_URLS = ("https://www2.census.gov/geo/tiger/GENZ2024/shp/cb_2024_us_state_20m.zip",
                  "https://www2.census.gov/geo/tiger/GENZ2023/shp/cb_2023_us_state_20m.zip")
FCC_USER_AGENT = "FamilyCoverage-server (+https://github.com/sstangle73/family-coverage)"
FCC_PAUSE = 0.5                     # seconds between calls: the map rate-limits
FCC_BACKOFF = (2, 4, 8, 16)         # seconds before each retry of a 429 or 5xx
FCC_FIRST_DELAY, FCC_EVERY = 60, 24 * 3600
FCC_RES, FCC_MAX_ACCURACY = 9, 150  # the report's grid, and its rule for a reading's hexagon (metres)
FCC_PROVIDERS = {"131425": "verizon", "130077": "att", "130403": "tmobile"}
FCC_TIERS = {"4G LTE": 1, "5G-NR (7/1 Mbps)": 2, "5G-NR (35/3 Mbps)": 3}
# Each carrier's label, and the network codes (MCC+MNC) on its network, so a SIM's readings find their carrier's
# claims: Visible is on Verizon's network, Cricket on AT&T's.
FCC_NETWORKS = {
    "verizon": ("Verizon", "311480 310004 310012 311270 311271 311272 311273 311274 311275 311276 311277 311278 "
                           "311279 311280 311281 311282 311283 311284 311285 311286 311287 311288 311289 311390"),
    "att": ("AT&T", "310410 310150 310170 310280 310380 310560 310680 311180 313100 310030"),
    "tmobile": ("T-Mobile", "310260 310160 310200 310210 310220 310230 310240 310250 310270 310310 310490 310660 "
                            "310800 312250 311490 311660 311882 312530"),
}
FCC_SOURCE = "FCC National Broadband Map, Broadband Data Collection mobile coverage (providers' filings)"
_fcc_running = threading.Lock()
_fcc_meta = {}  # what /metrics last read from the layer, and the file's size and time then
_fcc_http = {}  # the requests session, made on first use


def fmt_size(n):
    return f"{n / 1e6:.1f} MB" if n >= 100_000 else f"{n / 1e3:.1f} KB"


def write_atomic(path, data):
    """Writes a file whole or not at all: a reader sees the old one or the new one."""
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_bytes(data)
    os.replace(tmp, path)


def _float(s):
    try:
        v = float(s)
    except (TypeError, ValueError):
        return None
    return v if math.isfinite(v) else None


def fcc_missing():
    """Which of the packages that only the FCC layer needs, h3 and requests, aren't installed."""
    missing = []
    for name in ("h3", "requests"):
        try:
            __import__(name)
        except ImportError:
            missing.append(name)
    return missing


def fcc_needs(missing):
    s = "s" if len(missing) > 1 else ""
    return f"the FCC layer needs the {' and '.join(missing)} package{s} (pip install {' '.join(missing)})"


def fcc_h3():
    """The h3 package, imported only when the FCC layer is built: nothing else needs it."""
    try:
        import h3
    except ImportError:
        raise RuntimeError(fcc_needs(fcc_missing())) from None
    if not hasattr(h3, "latlng_to_cell"):
        raise RuntimeError(f"the FCC layer needs h3 version 4, not {getattr(h3, '__version__', 'an older one')}")
    return h3


class _FccResponse:
    """A requests response, read like a file: the builder reads every answer that way (and the tests' are files)."""

    def __init__(self, r):
        self.r, self.status, self.headers = r, r.status_code, r.headers
        if (r.headers.get("Content-Encoding") or "identity").lower() != "identity":
            # Compressed on the way and unpacked here: the length sent is the compressed one, so it can't check a file.
            self.headers = type(r.headers)(r.headers)
            self.headers.pop("Content-Length", None)
        self.chunks, self.buf = r.iter_content(1 << 20), bytearray()

    def read(self, n=-1):
        while n < 0 or len(self.buf) < n:
            block = next(self.chunks, b"")
            if not block:
                break
            self.buf += block
        n = len(self.buf) if n < 0 else min(n, len(self.buf))
        out = bytes(self.buf[:n])
        del self.buf[:n]
        return out

    def close(self):
        self.r.close()

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


def fcc_fetch(url, headers, timeout):
    """One GET, with requests: the answer read like a file, or HTTPError for any answer but 2xx. Redirects are followed
    only within FCC_HOSTS. The tests swap this out, so they never go online, nor need requests."""
    try:
        import requests
    except ImportError:
        raise RuntimeError(fcc_needs(["requests"])) from None
    session = _fcc_http.get("session") or _fcc_http.setdefault("session", requests.Session())
    for _ in range(5):
        r = session.get(url, headers=headers, timeout=timeout, stream=True, allow_redirects=False)
        if r.is_redirect:
            r.close()
            url = urllib.parse.urljoin(url, r.headers["Location"])
            if urllib.parse.urlsplit(url).hostname not in FCC_HOSTS:
                raise urllib.error.HTTPError(url, r.status_code, "a redirect off the FCC's and the Census Bureau's "
                                             "sites", r.headers, None)
            continue
        if not 200 <= r.status_code < 300:
            r.close()
            raise urllib.error.HTTPError(url, r.status_code, r.reason or "", r.headers, None)
        return _FccResponse(r)
    raise urllib.error.HTTPError(url, r.status_code, "too many redirects", r.headers, None)


class FccClient:
    """GETs from the FCC's map and the Census Bureau. The map answers 403 without its own site as the Referer, and it
    rate-limits: calls are FCC_PAUSE apart, and a 429 or 5xx (or no answer at all) is tried again after FCC_BACKOFF."""

    def __init__(self, fetch=None, pause=None, sleep=time.sleep):
        self.fetch, self.sleep = fetch or fcc_fetch, sleep
        self.pause = FCC_PAUSE if pause is None else pause
        self.last = float("-inf")

    def get(self, url, timeout=120):
        host = urllib.parse.urlsplit(url).hostname
        if host not in FCC_HOSTS:
            raise ValueError(f"the FCC layer downloads only from {' and '.join(FCC_HOSTS)}, not {host}")
        headers = {"User-Agent": FCC_USER_AGENT, "Accept": "application/json, */*"}
        if host == "broadbandmap.fcc.gov":
            headers["Referer"] = "https://broadbandmap.fcc.gov/"
        for backoff in FCC_BACKOFF + (None,):
            wait = self.pause - (time.monotonic() - self.last)
            if wait > 0:
                self.sleep(wait)
            try:
                return self.fetch(url, headers, timeout)
            except urllib.error.HTTPError as e:
                e.close()
                if backoff is None or (e.code != 429 and e.code < 500):
                    raise
            except OSError:  # no answer at all: a timeout, a dropped connection, a DNS hiccup
                if backoff is None:
                    raise
            finally:
                self.last = time.monotonic()
            self.sleep(backoff)

    def data(self, path):
        """The "data" list the map's API answers with."""
        with self.get(FCC_API + path) as r:
            body = json.loads(r.read().decode("utf-8"))
        rows = body.get("data") if isinstance(body, dict) else None
        if not isinstance(rows, list):
            raise ValueError(f"{path}: the FCC's answer isn't in the shape this server knows")
        return rows


def read_dbf(fh, names, keep=None):
    """The named fields of a dBase table's live records, streamed from fh: (record number, [stripped strings]) each.

    keep: the values of the first named field to keep, as bytes. Other records are skipped before anything is decoded,
    which keeps a scan of a million-row table to about a second.
    """
    head = fh.read(32)
    if len(head) < 32:
        raise ValueError("not a dBase table")
    count, head_len, rec_len = struct.unpack("<IHH", head[4:12])
    if rec_len < 1 or head_len < 33:
        raise ValueError("not a dBase table")
    desc, fields, at = fh.read(head_len - 32), {}, 1  # each record starts with its deletion flag
    for i in range(0, len(desc) - 31, 32):
        if desc[i] == 0x0D:
            break
        fields[desc[i:i + 11].split(b"\0", 1)[0].decode("latin-1").strip().lower()] = (at, desc[i + 16])
        at += desc[i + 16]
    try:
        cols = [fields[n.lower()] for n in names]
    except KeyError as e:
        raise ValueError(f"the table has no field {e.args[0]}") from None
    kat, klen = cols[0]
    per_block, done = max(1, (1 << 20) // rec_len), 0
    while done < count:
        n = min(per_block, count - done)
        block = fh.read(n * rec_len)
        if len(block) < n * rec_len:
            raise ValueError(f"the table ends after {done + len(block) // rec_len} of its {count} records")
        for o in range(0, len(block), rec_len):
            if block[o] == 0x2A:  # "*": deleted
                continue
            if keep is not None and block[o + kat:o + kat + klen].rstrip(b" \0") not in keep:
                continue
            yield done + o // rec_len, [block[o + a:o + a + w].strip(b" \0").decode("latin-1") for a, w in cols]
        done += n
    fh.read()  # on to the end, so a zip member's CRC is checked: a damaged download fails here, not quietly


def read_shp(data):
    """A polygon shapefile's records: (bbox, [(ring bbox, [(x, y), ...]), ...]) each, None for an empty one. x is the
    longitude."""
    if len(data) < 100 or struct.unpack(">i", data[:4])[0] != 9994:
        raise ValueError("not a shapefile")
    out, pos = [], 100
    while pos + 8 <= len(data):
        words = struct.unpack(">i", data[pos + 4:pos + 8])[0]
        rec, pos = data[pos + 8:pos + 8 + 2 * words], pos + 8 + 2 * words
        kind = struct.unpack("<i", rec[:4])[0]
        if kind == 0:
            out.append(None)
            continue
        if kind not in (5, 15, 25):  # polygon, polygon Z, polygon M: x and y come first in all three
            raise ValueError(f"shape type {kind} isn't a polygon")
        bbox = struct.unpack("<4d", rec[4:36])
        nparts, npoints = struct.unpack("<2i", rec[36:44])
        starts = struct.unpack(f"<{nparts}i", rec[44:44 + 4 * nparts]) + (npoints,)
        xy = struct.unpack(f"<{2 * npoints}d", rec[44 + 4 * nparts:44 + 4 * nparts + 16 * npoints])
        rings = []
        for a, b in zip(starts, starts[1:]):
            xs, ys = xy[2 * a:2 * b:2], xy[2 * a + 1:2 * b:2]
            if xs:
                rings.append(((min(xs), min(ys), max(xs), max(ys)), list(zip(xs, ys))))
        out.append((bbox, rings))
    return out


def in_polygon(x, y, rings):
    """The even-odd rule over every ring, outer rings and holes alike: inside if a ray east crosses an odd number of
    edges."""
    inside = False
    for (x0, y0, x1, y1), ring in rings:
        if not (x0 <= x <= x1 and y0 <= y <= y1):
            continue  # a ring that can't hold the point crosses the ray an even number of times
        px, py = ring[-1]
        for qx, qy in ring:
            if (qy > y) != (py > y) and x < (px - qx) * (y - qy) / (py - qy) + qx:
                inside = not inside
            px, py = qx, qy
    return inside


def state_at(states, lon, lat):
    """The FIPS code of the state holding a point, or None (offshore, or abroad)."""
    for fips, _, (x0, y0, x1, y1), rings in states:
        if x0 <= lon <= x1 and y0 <= lat <= y1 and in_polygon(lon, lat, rings):
            return fips
    return None


def fcc_states(client):
    """The Census Bureau's state outlines, downloaded once into the cache: [(FIPS, abbreviation, bbox, rings)]."""
    path = next((p for p in (FCC_CACHE / u.rsplit("/", 1)[1] for u in FCC_STATE_URLS) if p.is_file()), None)
    if path is None:
        path = fcc_states_download(client)
    with zipfile.ZipFile(path) as z:
        shp = next((n for n in z.namelist() if n.lower().endswith(".shp")), None)
        dbf = next((n for n in z.namelist() if n.lower().endswith(".dbf")), None)
        if not shp or not dbf:
            raise ValueError(f"{path.name} isn't a shapefile of the states")
        shapes = read_shp(z.read(shp))
        with z.open(dbf) as fh:
            names = dict(read_dbf(fh, ("STATEFP", "STUSPS")))
    return [(names[i][0].zfill(2), names[i][1], *shape) for i, shape in enumerate(shapes) if shape and i in names]


def fcc_states_download(client):
    err = None
    for url in FCC_STATE_URLS:
        try:
            with client.get(url) as r:
                data = r.read()
        except urllib.error.HTTPError as e:  # not published yet, or moved: the year before
            err = e
            continue
        if not zipfile.is_zipfile(io.BytesIO(data)):
            err = ValueError(f"{url} isn't a zip")
            continue
        path = FCC_CACHE / url.rsplit("/", 1)[1]
        write_atomic(path, data)
        log(f"fcc: state outlines: {path.name}, {fmt_size(len(data))}")
        return path
    raise err


def fcc_allowed(states):
    """FC_FCC_STATES as FIPS codes, or None for every state."""
    if not FCC_STATES.strip():
        return None
    by_abbr = {abbr.upper(): fips for fips, abbr, _, _ in states}
    out = set()
    for tok in (t.strip().upper() for t in FCC_STATES.split(",")):
        fips = tok.zfill(2) if tok.isdigit() else by_abbr.get(tok)
        if fips in by_abbr.values():
            out.add(fips)
        elif tok:
            log(f"fcc: FC_FCC_STATES: {tok} isn't a state, so it's left out")
    return out


def fcc_cells(h3):
    """The resolution-9 hexagons the report can draw: each stored reading's, unless its fix is vaguer than 150 m (the
    report's own rule). The report draws the FCC's tiers only where there are readings, so these are all it needs."""
    cells = set()
    for _, _, dev_dir in device_dirs():
        for f in sorted(dev_dir.glob("samples-*.csv")):
            if not NAME.match(f.name):
                continue
            rows = csv.reader(io.StringIO(complete_rows(f).decode("utf-8", "replace")))
            header = next(rows, [])
            if "lat" not in header or "lon" not in header:
                continue
            ilat, ilon = header.index("lat"), header.index("lon")
            iacc = header.index("accuracy_m") if "accuracy_m" in header else None
            last, seen = max(ilat, ilon, iacc or 0), set()  # a phone sitting still repeats its position
            for r in rows:
                if len(r) <= last:
                    continue
                acc = _float(r[iacc]) if iacc is not None else None
                if (acc is not None and acc > FCC_MAX_ACCURACY) or (r[ilat], r[ilon]) in seen:
                    continue
                seen.add((r[ilat], r[ilon]))
                lat, lon = _float(r[ilat]), _float(r[ilon])
                if lat is not None and lon is not None and abs(lat) <= 90 and abs(lon) <= 180:
                    cells.add(h3.latlng_to_cell(lat, lon, FCC_RES))
    return cells


def fcc_vintage(client):
    """(date, process uuid) of the newest biannual filing the map has published, such as ("2025-12-31", "6aec...")."""
    best = None
    for path in ("published/filing", "published/downloads"):
        for f in client.data(path):
            if not isinstance(f, dict) or f.get("filing_type") != "Biannual" \
                    or not re.fullmatch(r"[0-9A-Fa-f-]{8,64}", str(f.get("process_uuid"))):
                continue
            try:
                day = datetime.strptime(str(f.get("filing_subtype")), "%B %d, %Y").date().isoformat()
            except ValueError:
                continue
            if best is None or day >= best[0]:
                best = (day, str(f["process_uuid"]))
    if best is None:
        raise ValueError("the FCC's map lists no published biannual filing")
    return best


def fcc_listing(client, vdir, uuid, fresh=False):
    """The vintage's list of downloadable files, about 10,600 rows and 5 MB, so it's kept: (rows, from the cache?)."""
    path = vdir / "listing.json"
    if not fresh:
        try:
            saved = json.loads(path.read_text(encoding="utf-8"))
            if saved["process_uuid"] == uuid and isinstance(saved["data"], list):
                return saved["data"], True
        except (OSError, ValueError, KeyError, TypeError):
            pass
    rows = client.data(f"national_map_process/nbm_get_data_download/{uuid}")
    vdir.mkdir(parents=True, exist_ok=True)
    write_atomic(path, json.dumps({"process_uuid": uuid, "fetched": now_iso(), "data": rows}).encode("utf-8"))
    return rows, False


def fcc_pick(rows, fips):
    """The state's H3 files for the three carriers, and the carriers that have no 4G LTE file there. Without one, all
    of that carrier's hexagons would wrongly read as claiming nothing, so such a state isn't used."""
    picked = {}
    for x in rows:
        if not isinstance(x, dict):
            continue
        pid, tech, fid = str(x.get("provider_id")), x.get("technology_code_desc"), str(x.get("id"))
        if (x.get("data_category") == "Provider" and x.get("data_type") == "Mobile Broadband"
                and str(x.get("state_fips")).zfill(2) == fips and x.get("download_available") == "Yes"
                and pid in FCC_PROVIDERS and tech in FCC_TIERS and "_h3_" in str(x.get("file_name"))
                and fid.isascii() and fid.isdigit()):
            picked[fid] = {"id": fid, "network": FCC_PROVIDERS[pid], "tier": FCC_TIERS[tech], "technology": tech}
    order = list(FCC_NETWORKS)
    files = sorted(picked.values(), key=lambda p: (order.index(p["network"]), p["tier"], int(p["id"])))
    lte = {p["network"] for p in files if p["tier"] == 1}
    return files, [n for n in FCC_NETWORKS if n not in lte]


def fcc_download(client, f, sdir, abbr):
    """One H3 file into the state's folder, named as the FCC names it: (name, bytes). A file already there with the size
    the FCC gives is kept, so an interrupted build carries on where it stopped."""
    what = f"fcc: {abbr} {FCC_NETWORKS[f['network']][0]} {f['technology']}"
    with client.get(f"{FCC_API}getNBMDataDownloadFile/{f['id']}/1", timeout=300) as r:
        m = re.search(r'filename="?([^";]+)"?', r.headers.get("Content-Disposition") or "")
        name = m.group(1).strip() if m else ""
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,199}", name):
            name = f"{f['id']}.zip"
        path, part = sdir / name, sdir / (name + ".part")
        length = r.headers.get("Content-Length") or ""
        size = int(length) if length.isdigit() else None
        if size is not None and path.is_file() and path.stat().st_size == size:
            log(f"{what}: {name}, {fmt_size(size)}, already here")
            return name, size
        got = 0
        with open(part, "wb") as fh:
            for block in iter(lambda: r.read(1 << 20), b""):
                fh.write(block)
                got += len(block)
    if (size is not None and got != size) or not zipfile.is_zipfile(part):
        part.unlink(missing_ok=True)
        raise ValueError(f"{name}: the download came out wrong ({got} bytes of {size})")
    os.replace(part, path)
    log(f"{what}: {name}, {fmt_size(got)}")
    return name, got


def fcc_done(sdir):
    """The state's done.json, when every file it lists is still there."""
    try:
        done = json.loads((sdir / "done.json").read_text(encoding="utf-8"))
        return done if done["files"] and all((sdir / f["file"]).is_file() for f in done["files"]) else None
    except (OSError, ValueError, KeyError, TypeError):
        return None


def fcc_state(client, vdir, uuid, fips, abbr, memo):
    """One state's files, downloaded once: (done, None), or (None, why the state is left out). done.json, the list of
    the files, is written only once they've all arrived, so an interrupted download carries on next time."""
    sdir = vdir / fips
    done = fcc_done(sdir)
    if done:
        return done, None
    for attempt in (1, 2):
        if attempt == 2 or "rows" not in memo:
            memo["rows"], memo["saved"] = fcc_listing(client, vdir, uuid, fresh=attempt == 2)
        picked, missing = fcc_pick(memo["rows"], fips)
        if missing:
            return None, "no 4G LTE file for " + " or ".join(FCC_NETWORKS[n][0] for n in missing)
        sdir.mkdir(parents=True, exist_ok=True)
        try:
            files = []
            for f in picked:
                name, size = fcc_download(client, f, sdir, abbr)
                files.append(dict(f, file=name, bytes=size))
            break
        except urllib.error.HTTPError:
            if attempt == 2 or not memo["saved"]:
                raise
            log("fcc: a download failed with the saved list of files, so it's fetched again (the FCC renumbers its "
                "files when it re-processes a vintage)")
    done = {"vintage": vdir.name, "process_uuid": uuid, "state": fips, "abbr": abbr, "completed": now_iso(),
            "files": files}
    write_atomic(sdir / "done.json", json.dumps(done, indent=1).encode("utf-8"))
    log(f"fcc: {abbr}: {len(files)} files, {fmt_size(sum(f['bytes'] for f in files))}")
    return done, None


def fcc_tiers(vdir, have, domain):
    """Each carrier's best tier in each hexagon of `domain`: {network: {"o": {cell: tier}, "v": {cell: tier}}}.

    "o" (outdoors, standing still) is the best over all of a hexagon's rows; "v" over those also modelled for a moving
    car (environmnt 1). Only each file's .dbf table is read. Every state's files are read for every hexagon: the Census
    outlines are simplified, so a hexagon near a state line can be in the neighbour's files.
    """
    keep = {c.encode("ascii") for c in domain} | {c.upper().encode("ascii") for c in domain}
    out = {n: {"o": {}, "v": {}} for n in FCC_NETWORKS}
    for fips, done in sorted(have.items()):
        for f in done["files"]:
            o, v, tier, path = out[f["network"]]["o"], out[f["network"]]["v"], f["tier"], vdir / fips / f["file"]
            try:
                with zipfile.ZipFile(path) as z:
                    tables = [n for n in z.namelist() if n.lower().endswith(".dbf")]
                    if not tables:
                        raise ValueError(f"{path.name} has no .dbf table")
                    for member in tables:
                        with z.open(member) as fh:
                            for _, (cell, env) in read_dbf(fh, ("h3_res9_id", "environmnt"), keep):
                                cell = cell.lower()
                                if tier > o.get(cell, 0):
                                    o[cell] = tier
                                if _float(env) == 1 and tier > v.get(cell, 0):
                                    v[cell] = tier
            except (zipfile.BadZipFile, zlib.error, EOFError) as e:  # damaged: dropped, for the next build to fetch
                path.unlink(missing_ok=True)
                (vdir / fips / "done.json").unlink(missing_ok=True)
                raise ValueError(f"{path.name} is damaged ({e}), so it's deleted for the next build to fetch again") \
                    from None
    return out


def fcc_layer(h3, vintage, states, domain, tiers):
    """fcc.json's content, in docs/data-format.md's shape: each carrier's hexagons by the best tier it claims there."""
    def compact(cells):
        return sorted(h3.compact_cells(sorted(cells)))

    nets = {}
    for key, (label, plmns) in FCC_NETWORKS.items():
        nets[key] = {"label": label, "plmns": plmns.split()}
        for env in ("o", "v"):
            best = tiers[key][env]
            nets[key][env] = {str(t): compact(c for c in domain if best.get(c) == t) for t in (1, 2, 3)}
    return {"format": "family-coverage-fcc", "version": 1, "vintage": vintage,
            "updated": datetime.now().astimezone().isoformat(timespec="seconds"), "source": FCC_SOURCE,
            "states": sorted(states), "networks": nets, "domain": compact(domain)}


def build_fcc(client=None):
    """Builds fcc.json once: the newest vintage's claims of the three carriers, in every hexagon with readings that
    lies in a state whose files are complete. Returns a summary; raises on failure, and the old fcc.json stays."""
    h3 = fcc_h3()
    if not _fcc_running.acquire(blocking=False):
        raise RuntimeError("a build is already running")
    try:
        FCC_CACHE.mkdir(parents=True, exist_ok=True)
        with open(FCC_CACHE / "build.lock", "a") as lock:
            try:
                import fcntl  # so build-fcc and the server's own builder never download the same file at once
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except ImportError:  # Windows: one build per process is all that's checked
                pass
            except OSError:
                raise RuntimeError("another build is running (the server's own, or build-fcc)") from None
            return _build_fcc(h3, client or FccClient())
    finally:
        _fcc_running.release()


def _build_fcc(h3, client):
    started = time.time()
    cells = fcc_cells(h3)  # before anything goes online: with no readings, nothing does
    s = {"cells": len(cells), "written": False, "states": [], "skipped": {}, "domain": 0, "in_skipped": 0,
         "outside": 0}
    if not cells:
        s["why"] = "there are no readings with a position yet"
        return s
    states = fcc_states(client)
    abbr = {fips: ab for fips, ab, _, _ in states}
    by_state = {}
    for c in cells:
        lat, lon = h3.cell_to_latlng(c)
        by_state.setdefault(state_at(states, lon, lat), set()).add(c)
    s["outside"] = len(by_state.pop(None, ()))
    allowed = fcc_allowed(states)
    skipped = {f: "not in FC_FCC_STATES" for f in by_state if allowed is not None and f not in allowed}
    have = {}
    wanted = sorted(f for f in by_state if f not in skipped)
    if wanted:
        s["vintage"], uuid = fcc_vintage(client)
        memo = {}
        for fips in wanted:
            done, why = fcc_state(client, FCC_CACHE / s["vintage"], uuid, fips, abbr[fips], memo)
            if done:
                have[fips] = done
            else:
                skipped[fips] = why
    domain = set().union(*(by_state[f] for f in have))
    s.update(states=[abbr[f] for f in sorted(have)], skipped={abbr[f]: why for f, why in sorted(skipped.items())},
             domain=len(domain), in_skipped=len(cells) - len(domain) - s["outside"])
    if not domain:
        s["why"] = "no hexagon with readings is in a state with the FCC's files"
        return s
    tiers = fcc_tiers(FCC_CACHE / s["vintage"], have, domain)
    data = json.dumps(fcc_layer(h3, s["vintage"], have, domain, tiers), separators=(",", ":")).encode("utf-8")
    FCC_FILE.parent.mkdir(parents=True, exist_ok=True)
    write_atomic(FCC_FILE, data)
    s.update(written=True, bytes=len(data), seconds=round(time.time() - started),
             networks={n: {e: [sum(1 for c in domain if tiers[n][e].get(c) == t) for t in (1, 2, 3)] for e in "ov"}
                       for n in FCC_NETWORKS})
    return s


def fcc_summary(s):
    """A build in one line, for the log and build-fcc: the vintage, the states used and the ones left out (and why),
    the hexagons, and the file's size."""
    if not s["cells"]:
        return "fcc: nothing to build, as there are no readings with a position yet"
    head = (f"fcc: built {FCC_FILE.name} ({fmt_size(s['bytes'])}) in {s['seconds']} s" if s["written"]
            else f"fcc: {FCC_FILE.name} left as it was, as {s['why']}")
    parts = [f"vintage {s['vintage']}" if s.get("vintage") else "",
             "data for " + (", ".join(s["states"]) or "no state"),
             "skipped " + ", ".join(f"{k} ({v})" for k, v in s["skipped"].items()) if s["skipped"] else "",
             f"hexagons with readings {s['cells']}: in the layer {s['domain']}, in skipped states {s['in_skipped']}, "
             f"in no state {s['outside']}"]
    return head + ": " + "; ".join(p for p in parts if p)


def fcc_network_lines(s):
    """Each carrier's claims among the layer's hexagons, for build-fcc."""
    return [f"  {FCC_NETWORKS[key][0]}: outdoors {sum(n['o'])} of {s['domain']} claimed (5G 35/3 Mbps {n['o'][2]}, "
            f"5G 7/1 Mbps {n['o'][1]}, LTE {n['o'][0]}), in a moving car {sum(n['v'])}"
            for key, n in s.get("networks", {}).items()]


def fcc_failed(e):
    where = f" ({e.url})" if isinstance(e, urllib.error.HTTPError) else ""
    return f"fcc: build failed, so {FCC_FILE.name} stays as it was: {type(e).__name__}: {e}{where}"


def start_fcc_builder():
    """With FC_FCC=auto, a thread that builds the FCC layer about a minute after startup, then every 24 hours."""
    if FCC_MODE in ("", "off"):
        return None
    if FCC_MODE != "auto":
        log(f"fcc: FC_FCC={FCC_MODE} isn't a setting this server knows (auto turns the FCC layer on), so it stays off")
        return None
    try:
        if fcc_missing():
            raise RuntimeError(fcc_needs(fcc_missing()))
        fcc_h3()
    except RuntimeError as e:
        log(f"fcc: FC_FCC=auto, but {e}; everything else runs as usual")
        return None
    log(f"fcc: building the FCC layer in {FCC_FIRST_DELAY} s, then every {FCC_EVERY // 3600} h")
    t = threading.Thread(target=_fcc_loop, name="fcc", daemon=True)
    t.start()
    return t


def _fcc_loop():
    time.sleep(FCC_FIRST_DELAY)
    while True:
        try:
            log(fcc_summary(build_fcc()))
        except Exception as e:  # the network, the FCC's site, the disk: one line, and the next build tries again
            log(fcc_failed(e))
        time.sleep(FCC_EVERY)


def fcc_metrics():
    """/metrics lines for the FCC layer, when there is one: when it was written, and how many states it covers."""
    try:
        st = FCC_FILE.stat()
    except OSError:
        return []
    key = (str(FCC_FILE), st.st_size, st.st_mtime_ns)
    if _fcc_meta.get("key") != key:
        try:
            states = json.loads(FCC_FILE.read_bytes()).get("states")
        except (OSError, ValueError, AttributeError):
            states = None
        _fcc_meta.update(key=key, states=len(states) if isinstance(states, list) else None)
    lines = ["# HELP familycoverage_fcc_layer_built_timestamp_seconds When the FCC layer (fcc.json) was last written.",
             "# TYPE familycoverage_fcc_layer_built_timestamp_seconds gauge",
             f"familycoverage_fcc_layer_built_timestamp_seconds {int(st.st_mtime)}"]
    if _fcc_meta["states"] is not None:
        lines += ["# HELP familycoverage_fcc_layer_states States the FCC layer has the carriers' claims for.",
                  "# TYPE familycoverage_fcc_layer_states gauge",
                  f"familycoverage_fcc_layer_states {_fcc_meta['states']}"]
    return lines


def cli(argv):
    cmd = argv[1] if len(argv) > 1 else "serve"
    if cmd == "serve":
        host, port = LISTEN.rsplit(":", 1)
        DATA.mkdir(parents=True, exist_ok=True)
        httpd = Server((host.strip("[]"), int(port)), Handler)
        sys.stderr.write(f"familycoverage: listening on {LISTEN}, data {DATA}\n")
        start_fcc_builder()
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
    elif cmd == "build-fcc":
        # One build, whatever FC_FCC says: for cron, instead of the server's own daily build.
        try:
            s = build_fcc()
        except Exception as e:
            sys.exit(fcc_failed(e))
        print(fcc_summary(s))
        for line in fcc_network_lines(s):
            print(line)
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    cli(sys.argv)
