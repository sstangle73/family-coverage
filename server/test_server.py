"""Tests for the Family Coverage server: python -m unittest discover -s server -p "test_*.py"."""
import gzip
import io
import hashlib
import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

sys.path.insert(0, os.path.dirname(__file__))
import familycoverage_server as srv  # noqa: E402

KEY = "ab" * 32
DEVICE = hashlib.sha256(KEY.encode()).hexdigest()[:16]


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        srv.DATA = Path(self.tmp.name)
        srv.EXPORT_TOKEN_FILE = srv.DATA / "export_token"
        srv.REPORT_PASSWORD_FILE = srv.DATA / "report_password"
        srv.FCC_FILE = srv.DATA / "fcc.json"
        srv.FAILS.clear()
        srv.SESSIONS.clear()
        srv.HOUSEHOLD = ""
        srv.API_NETS = srv._nets("0.0.0.0/0,::/0")
        srv.OPEN_NETS = srv._nets("127.0.0.0/8")
        self.httpd = srv.Server(("127.0.0.1", 0), srv.Handler)
        self.base = f"http://127.0.0.1:{self.httpd.server_address[1]}"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def tearDown(self):
        self.httpd.shutdown()
        self.httpd.server_close()
        self.tmp.cleanup()

    def call(self, method, path, body=None, key=None, gz=True, headers=None, raw=None):
        data = raw
        h = dict(headers or {})
        if body is not None:
            data = json.dumps(body).encode()
            h["Content-Type"] = "application/json"
            if gz:
                data = gzip.compress(data)
                h["Content-Encoding"] = "gzip"
        if key:
            h["Authorization"] = f"Bearer {key}"
        req = urllib.request.Request(self.base + path, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=10) as r:
                return r.status, r.read(), dict(r.headers)
        except urllib.error.HTTPError as e:
            return e.code, e.read(), dict(e.headers)

    def register(self, member="Sam", household="0a1b2c3d"):
        return self.call("POST", "/api/register", {"member": member, "household": household, "key": KEY,
                                                    "model": "Pixel", "app_version": "0.1.5", "consent_at": "x"})

    def approve(self):
        srv.cli(["x", "approve", DEVICE])

    def test_register_then_upload_only_after_approval(self):
        code, body, _ = self.register()
        self.assertEqual(200, code)
        self.assertEqual({"device_id": DEVICE, "status": "pending"}, json.loads(body))
        entry = {"name": "samples-2026-10-01.csv", "offset": 0, "data": "ts,member\n1,Sam\n"}
        self.assertEqual(403, self.call("POST", "/api/upload", {"files": [entry]}, key=KEY)[0])
        self.approve()
        code, body, _ = self.call("POST", "/api/upload", {"files": [entry]}, key=KEY,
                                  headers={"User-Agent": "FamilyCoverage/0.1.6"})
        self.assertEqual(200, code)
        self.assertEqual(16, json.loads(body)["files"][0]["size"])
        # The same bytes again change nothing; the next row appends.
        self.call("POST", "/api/upload", {"files": [entry]}, key=KEY)
        more = {"name": "samples-2026-10-01.csv", "offset": 16, "data": "2,Sam\n"}
        code, body, _ = self.call("POST", "/api/upload", {"files": [more]}, key=KEY)
        f = srv.DATA / "sam" / DEVICE / "samples-2026-10-01.csv"
        self.assertEqual("ts,member\n1,Sam\n2,Sam\n", f.read_text())
        self.assertEqual("0.1.6", srv.load_devices()[DEVICE]["app_version"])

    def test_bad_requests_are_refused(self):
        self.assertEqual(400, self.call("POST", "/api/register", {"member": "", "key": KEY})[0])
        self.assertEqual(400, self.call("POST", "/api/register", {"member": "Sam", "key": "nope"})[0])
        self.register()
        self.approve()
        for bad in ({"name": "../evil.csv", "offset": 0, "data": "x\n"},
                    {"name": "secrets-2026-10-01.csv", "offset": 0, "data": "x\n"},
                    {"name": "samples-2026-10-01.csv", "offset": -1, "data": "x\n"}):
            self.assertEqual(400, self.call("POST", "/api/upload", {"files": [bad]}, key=KEY)[0], bad)
        self.assertEqual(401, self.call("POST", "/api/upload", {"files": []}, key="cd" * 32)[0])
        self.assertEqual(400, self.call("POST", "/api/upload", None, key=KEY, raw=b"not gzip",
                                        headers={"Content-Encoding": "gzip"})[0])

    def test_hello_says_what_it_is_without_naming_the_household(self):
        hello = lambda q="": json.loads(self.call("GET", "/api/hello" + q)[1])
        self.assertEqual({"app": "family-coverage", "api": 1, "household": "any"}, hello("?household=0a1b2c3d"))
        srv.HOUSEHOLD = "0a1b2c3d"
        self.assertEqual("ok", hello("?household=0A1B2C3D")["household"])
        self.assertEqual("other", hello("?household=ffffffff")["household"])
        self.assertEqual("locked", hello()["household"])
        self.assertNotIn("0a1b2c3d", json.dumps(hello()))
        srv.API_NETS = srv._nets("10.0.0.0/8")  # a network the server doesn't serve: refused like the rest of /api
        self.assertEqual(403, self.call("GET", "/api/hello")[0])

    def test_household_lock_and_pending_cap(self):
        srv.HOUSEHOLD = "0a1b2c3d"
        self.assertEqual(403, self.register(household="ffffffff")[0])
        self.assertEqual(200, self.register()[0])
        old = srv.MAX_PENDING
        srv.MAX_PENDING = 1
        try:
            other = {"member": "Jo", "household": "0a1b2c3d", "key": "cd" * 32}
            self.assertEqual(429, self.call("POST", "/api/register", other)[0])
        finally:
            srv.MAX_PENDING = old

    def test_server_test_endpoints_need_an_approved_install(self):
        self.assertEqual(401, self.call("GET", "/api/test/ping")[0])
        self.register()
        self.assertEqual(403, self.call("GET", "/api/test/ping", key=KEY)[0])
        self.approve()
        self.assertEqual(200, self.call("GET", "/api/test/ping", key=KEY)[0])
        code, body, _ = self.call("GET", "/api/test/down?bytes=5000000", key=KEY)
        self.assertEqual(srv.MAX_TEST_DOWN, len(body))
        code, body, _ = self.call("POST", "/api/test/up", None, key=KEY, raw=bytes(125_000),
                                  headers={"Content-Type": "application/octet-stream"})
        self.assertEqual({"bytes": 125_000}, json.loads(body))
        # The path: this request didn't come over Tailscale.
        self.assertEqual(401, self.call("POST", "/api/test/begin", None, raw=b"")[0])
        code, body, _ = self.call("POST", "/api/test/begin", None, key=KEY, raw=b"")
        self.assertEqual(200, code)
        self.assertEqual("not_tailscale", json.loads(body)["path"])
        self.assertEqual("not_tailscale", json.loads(self.call("POST", "/api/test/end", None, key=KEY, raw=b"")[1])["path"])

    def test_tailnet_path_reads_tailscale_status(self):
        status = json.dumps({"Peer": {
            "a": {"TailscaleIPs": ["100.101.1.2", "fd7a:115c:a1e0::1"], "CurAddr": "8.8.8.8:41641", "Relay": "ord"},
            "b": {"TailscaleIPs": ["100.101.1.3"], "CurAddr": "[fd00::5]:41641", "Relay": "ord"},
            "c": {"TailscaleIPs": ["100.101.1.4"], "CurAddr": "", "Relay": "dfw", "Active": True},
            "d": {"TailscaleIPs": ["100.101.1.5"], "CurAddr": "", "Relay": "dfw", "PeerRelay": "100.101.1.9:7777"},
            "e": {"TailscaleIPs": ["100.101.1.6"], "Relay": "ord"},
        }})
        self.assertEqual({"path": "direct", "derp_region": "ord", "direct_family": "ipv4", "direct_lan": False},
                         srv.tailnet_path("100.101.1.2", status))
        self.assertEqual({"path": "direct", "derp_region": "ord", "direct_family": "ipv6", "direct_lan": True},
                         srv.tailnet_path("100.101.1.3", status))
        self.assertEqual("derp", srv.tailnet_path("100.101.1.4", status)["path"])
        self.assertEqual("peer_relay", srv.tailnet_path("100.101.1.5", status)["path"])
        self.assertEqual("idle", srv.tailnet_path("100.101.1.6", status)["path"])
        self.assertEqual("unknown", srv.tailnet_path("100.101.1.7", status)["path"])
        self.assertEqual("unknown", srv.tailnet_path("100.101.1.2", "not json")["path"])

    def test_export_api_and_zips(self):
        self.register()
        self.approve()
        rows = "ts,member\n1,Sam\n2,Sa"  # a row still arriving
        self.call("POST", "/api/upload", {"files": [{"name": "samples-2026-10-01.csv", "offset": 0, "data": rows}]}, key=KEY)
        manifest = {"format": "family-coverage-export", "version": 1, "member": "Sam",
                    "household": {"id": "0a1b2c3d", "name": "The Smiths", "places": []}}
        self.assertEqual(200, self.call("POST", "/api/manifest", manifest, key=KEY)[0])
        self.assertEqual(400, self.call("POST", "/api/manifest", {"format": "other"}, key=KEY)[0])
        self.assertEqual(503, self.call("GET", "/api/export/")[0])
        srv.cli(["x", "new-export-token"])
        token = srv.EXPORT_TOKEN_FILE.read_text().strip()
        self.assertEqual(401, self.call("GET", "/api/export/", key="wrong")[0])
        code, body, _ = self.call("GET", "/api/export/", key=token)
        listing = json.loads(body)
        self.assertEqual(["sam/%s/samples-2026-10-01.csv" % DEVICE], [f["path"] for f in listing["files"]])
        code, body, headers = self.call("GET", f"/api/export/sam/{DEVICE}/samples-2026-10-01.csv", key=token,
                                        headers={"Range": "bytes=10-"})
        self.assertEqual(206, code)
        self.assertEqual(rows[10:].encode(), body)
        self.assertEqual(404, self.call("GET", f"/api/export/../{DEVICE}/x.csv", key=token)[0])
        out = srv.DATA / "zips"
        written = srv.export_zips(out)
        self.assertEqual(1, len(written))
        with zipfile.ZipFile(written[0]) as z:
            m = json.loads(z.read("manifest.json"))
            self.assertEqual("The Smiths", m["household"]["name"])
            self.assertEqual(["csv/samples-2026-10-01.csv"], m["files"])
            self.assertEqual(b"ts,member\n1,Sam\n", z.read("csv/samples-2026-10-01.csv"))  # the part row waits

    def test_health_and_metrics_stay_private(self):
        self.assertEqual(200, self.call("GET", "/healthz")[0])
        srv.OPEN_NETS = srv._nets("10.0.0.0/8")
        self.assertEqual(403, self.call("GET", "/metrics")[0])
        srv.OPEN_NETS = srv._nets("127.0.0.0/8")
        self.register()
        self.approve()
        self.call("POST", "/api/upload", {"files": [{"name": "heartbeat-2026-10-01.csv", "offset": 0,
                                                     "data": "ts,member,logger_state\n2026-10-01T08:00:00-04:00,Sam,ENDED\n"}]}, key=KEY)
        code, body, _ = self.call("GET", "/metrics")
        text = body.decode()
        self.assertIn('familycoverage_ended{member="sam"} 1', text)
        self.assertIn('familycoverage_devices{status="approved"} 1', text)

    def test_live_report_needs_the_password(self):
        # The page itself has no data, and it's switched to live mode.
        code, body, _ = self.call("GET", "/report/")
        self.assertEqual(200, code)
        self.assertIn(b'window.FC_LIVE_API = "/api/report"', body)
        self.assertEqual(401, self.call("GET", "/api/report/devices")[0])
        login = lambda pw: self.call("POST", "/api/report/login", {"password": pw}, gz=False)
        self.assertEqual(503, login("anything at all")[0])  # no password set yet
        srv.write_password_hash("correct horse battery")
        if os.name == "posix":
            self.assertEqual(0o600, os.stat(srv.REPORT_PASSWORD_FILE).st_mode & 0o777)
        with self.assertRaises(ValueError):
            srv.write_password_hash("short")
        code, body, _ = login("correct horse battery")
        self.assertEqual(200, code)
        token = json.loads(body)["token"]
        # Data appears once a phone uploads; each phone comes as the same zip it would export.
        self.assertEqual({"devices": []}, json.loads(self.call("GET", "/api/report/devices", key=token)[1]))
        self.register()
        self.approve()
        rows = "ts,member\n1,Sam\n"
        self.call("POST", "/api/upload", {"files": [{"name": "samples-2026-10-01.csv", "offset": 0, "data": rows}]}, key=KEY)
        devices = json.loads(self.call("GET", "/api/report/devices", key=token)[1])["devices"]
        self.assertEqual([DEVICE], [d["id"] for d in devices])
        self.assertEqual("Sam", devices[0]["member"])
        version = devices[0]["version"]
        code, body, headers = self.call("GET", f"/api/report/zip/{DEVICE}", key=token)
        self.assertEqual(200, code)
        self.assertEqual("application/zip", headers["Content-Type"])
        with zipfile.ZipFile(io.BytesIO(body)) as z:
            self.assertEqual(rows.encode(), z.read("csv/samples-2026-10-01.csv"))
            self.assertEqual(DEVICE, json.loads(z.read("manifest.json"))["device_id"])
        # A new upload changes the phone's version, so the page fetches it again.
        self.call("POST", "/api/upload", {"files": [{"name": "samples-2026-10-01.csv", "offset": 16, "data": "2,Sam\n"}]}, key=KEY)
        again = json.loads(self.call("GET", "/api/report/devices", key=token)[1])["devices"][0]["version"]
        self.assertNotEqual(version, again)
        self.assertEqual(404, self.call("GET", "/api/report/zip/0000000000000000", key=token)[0])
        self.assertEqual(401, self.call("GET", f"/api/report/zip/{DEVICE}", key="not-a-session")[0])

    def test_report_serves_an_fcc_layer_when_present(self):
        srv.write_password_hash("correct horse battery")
        token = json.loads(self.call("POST", "/api/report/login", {"password": "correct horse battery"}, gz=False)[1])["token"]
        self.assertEqual(401, self.call("GET", "/api/report/fcc")[0])
        self.assertEqual(404, self.call("GET", "/api/report/fcc", key=token)[0])
        srv.FCC_FILE.write_text(json.dumps({"format": "family-coverage-fcc", "version": 1, "networks": {}}))
        code, body, _ = self.call("GET", "/api/report/fcc", key=token)
        self.assertEqual(200, code)
        self.assertEqual("family-coverage-fcc", json.loads(body)["format"])

    def test_report_logins_lock_after_five_wrong_passwords(self):
        srv.write_password_hash("correct horse battery")
        login = lambda pw: self.call("POST", "/api/report/login", {"password": pw}, gz=False)[0]
        self.assertEqual([401] * 5, [login("wrong password!") for _ in range(5)])
        self.assertEqual(429, login("correct horse battery"))  # locked, even with the right one
        srv.FAILS.clear()
        self.assertEqual(200, login("correct horse battery"))

    def test_root_points_to_the_report(self):
        req = urllib.request.Request(self.base + "/")
        opener = urllib.request.build_opener(type("NoRedirect", (urllib.request.HTTPRedirectHandler,), {
            "redirect_request": lambda *a, **k: None}))
        try:
            opener.open(req, timeout=10)
            self.fail("expected a redirect")
        except urllib.error.HTTPError as e:
            self.assertEqual(302, e.code)
            self.assertEqual("/report/", e.headers["Location"])

    def test_slugs(self):
        self.assertEqual("mary-ann", srv.slug("Mary Ann"))
        self.assertEqual("member", srv.slug("学校"))
        self.assertIsNone(srv.clean_member("bell\x07"))
        self.assertEqual("Mary Ann", srv.clean_member("  Mary   Ann "))


if __name__ == "__main__":
    unittest.main()
