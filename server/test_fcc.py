"""Tests for the server's FCC layer, with no network: python -m unittest discover -s server -p "test_*.py".

Everything is made here: a dBase writer, a shapefile of two square "states" (one with a lake), H3 files as the FCC ships
them (zips holding a .dbf), and a fake fetch that serves the Census file, the FCC's vintages, its list of files and the
files themselves. The tests that build a layer need h3, as the server does; without it they're skipped. None needs
requests: the real fetch is tried on a stand-in for it.
"""
import contextlib
import io
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import types
import unittest
import urllib.error
import urllib.parse
import zipfile
from datetime import datetime
from pathlib import Path
from unittest import mock

sys.path.insert(0, os.path.dirname(__file__))
import familycoverage_server as srv  # noqa: E402

try:
    import h3
except ImportError:
    h3 = None

# The app's samples header (Tables.SAMPLES in Rows.kt).
SAMPLES = ["ts", "member", "sub_id", "sub_label", "carrier", "mcc_mnc", "data_sim", "service_state", "voice_transport",
           "roaming", "wifi_connected", "rat", "band", "arfcn", "pci", "cell_id", "tac", "rsrp", "rsrq", "sinr",
           "nr_band", "nr_arfcn", "nr_pci", "nr_rsrp", "nr_rsrq", "nr_sinr", "cell_age_s",
           "lat", "lon", "accuracy_m", "altitude_m", "speed_mps", "fix_age_s",
           "display_override", "cc_count", "bw_mhz", "cell_service", "mode"]
API = srv.FCC_API
CENSUS_2024, CENSUS_2023 = srv.FCC_STATE_URLS
UUID = "0f0e0d0c-0b0a-4908-8706-050403020100"
OLD_UUID = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"
LISTING_URL = API + f"national_map_process/nbm_get_data_download/{UUID}"
FILINGS = [{"filing_type": "Biannual", "filing_subtype": "June 30, 2025", "process_uuid": OLD_UUID},
           {"filing_type": "Biannual", "filing_subtype": "December 31, 2025", "process_uuid": UUID},
           {"filing_type": "Challenge", "filing_subtype": "March 31, 2026",
            "process_uuid": "cccccccc-0000-4000-8000-000000000000"},
           {"filing_type": "Biannual", "filing_subtype": "someday", "process_uuid": OLD_UUID}]
DOWNLOADS = [{"filing_type": "Biannual", "filing_subtype": "June 30, 2025", "process_uuid": OLD_UUID}]
LTE, NR7, NR35 = "4G LTE", "5G-NR (7/1 Mbps)", "5G-NR (35/3 Mbps)"
VZ, ATT, TMO = 131425, 130077, 130403


def row(fid, state, provider, tech, kind="h3", available="Yes", category="Provider", dtype="Mobile Broadband"):
    """One entry of the FCC's list of files."""
    return {"id": fid, "data_category": category, "data_type": dtype, "technology_code_desc": tech,
            "state_fips": state, "provider_id": str(provider), "download_available": available, "file_type": "gis",
            "file_name": f"bdc_{state}_{provider}_x_mobile_broadband_{kind}_D25_15sep2026"}


LISTING = [
    row(101, "81", VZ, LTE), row(102, "81", VZ, NR7), row(103, "81", VZ, NR35),
    row(111, "81", ATT, LTE), row(112, "81", ATT, NR7),          # AT&T has no 35/3 file here: that's allowed
    row(121, "81", TMO, LTE), row(122, "81", TMO, NR7),
    # Not for the layer: fixed broadband, another provider, the raw polygons, not downloadable, a nationwide file.
    row(900, "81", VZ, LTE, dtype="Fixed Broadband"), row(901, "81", 999999, LTE), row(902, "81", VZ, LTE, kind="raw"),
    row(903, "81", TMO, NR35, available="No"), row(904, "81", VZ, LTE, category="Nationwide"),
    # East: T-Mobile has no 4G LTE file there, so East isn't used at all.
    row(201, "82", VZ, LTE), row(211, "82", ATT, LTE), row(222, "82", TMO, NR7),
]


def dbf(fields, rows, deleted=()):
    """A dBase III table: fields are (name, type, width), rows are tuples, `deleted` holds deleted rows' indexes."""
    rec_len = 1 + sum(w for _, _, w in fields)
    out = bytearray(struct.pack("<B3BIHH20x", 3, 126, 10, 1, len(rows), 32 + 32 * len(fields) + 1, rec_len))
    for name, kind, width in fields:
        out += struct.pack("<11sc4xBB14x", name.encode(), kind.encode(), width, 0)
    out += b"\r"
    for i, values in enumerate(rows):
        out += b"*" if i in deleted else b" "
        for (_, kind, width), v in zip(fields, values):
            s = str(v).encode("latin-1")
            out += s.ljust(width) if kind == "C" else s.rjust(width)
    return bytes(out + b"\x1a")


def shp(polygons):
    """A polygon shapefile: each polygon is a list of closed rings of (x, y)."""
    recs, allx, ally = b"", [], []
    for i, rings in enumerate(polygons, 1):
        pts = [p for ring in rings for p in ring]
        xs, ys = [p[0] for p in pts], [p[1] for p in pts]
        allx, ally = allx + xs, ally + ys
        starts = [sum(len(r) for r in rings[:k]) for k in range(len(rings))]
        body = struct.pack("<i4d2i", 5, min(xs), min(ys), max(xs), max(ys), len(rings), len(pts))
        body += struct.pack(f"<{len(starts)}i", *starts) + b"".join(struct.pack("<2d", *p) for p in pts)
        recs += struct.pack(">2i", i, len(body) // 2) + body
    head = struct.pack(">7i", 9994, 0, 0, 0, 0, 0, (100 + len(recs)) // 2)
    head += struct.pack("<2i8d", 1000, 5, min(allx), min(ally), max(allx), max(ally), 0, 0, 0, 0)
    return head + recs


def square(x0, y0, x1, y1):
    return [(x0, y0), (x0, y1), (x1, y1), (x1, y0), (x0, y0)]


def census_zip(name="cb_2024_us_state_20m"):
    """Two square states side by side: West (81, WS), with a lake, and East (82, ES)."""
    shapes = shp([[square(-80, 40, -79, 41), square(-79.7, 40.6, -79.5, 40.8)], [square(-79, 40, -78, 41)]])
    table = dbf([("STATEFP", "C", 2), ("STATENS", "C", 8), ("STUSPS", "C", 2), ("NAME", "C", 30)],
                [("81", "00000081", "WS", "West"), ("82", "00000082", "ES", "East")])
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr(name + ".shp", shapes)
        z.writestr(name + ".dbf", table)
        z.writestr(name + ".prj", 'GEOGCS["GCS_North_American_1983"]')
    return buf.getvalue()


def h3_zip(name, rows, deleted=()):
    """An H3 file as the FCC ships it: a zipped shapefile, of which the builder reads only the .dbf."""
    fields = [("frn", "C", 10), ("providerid", "N", 6), ("brandname", "C", 20), ("technology", "N", 3),
              ("mindown", "N", 5), ("minup", "N", 5), ("minsignal", "N", 4), ("environmnt", "N", 1),
              ("h3_res9_id", "C", 15)]
    table = dbf(fields, [("0000000000", 1, "Brand", 400, 5, 1, -100, env, cell) for cell, env in rows], deleted)
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr(name + ".shp", b"the hexagons' outlines: never read")
        z.writestr(name + ".dbf", table)
    return buf.getvalue()


class Resp(io.BytesIO):
    """A urllib response, as far as the builder uses one; counts the reads of its body."""

    def __init__(self, data, headers=None):
        super().__init__(data)
        self.headers, self.status, self.reads = dict(headers or {}), 200, 0

    def read(self, n=-1):
        self.reads += 1
        return super().read(n)


class FakeNet:
    """The FCC's map and the Census Bureau, from the fixtures. Records every call with its headers."""

    def __init__(self, files, census_2024=True):
        self.listing, self.files, self.census_2024 = [dict(r) for r in LISTING], dict(files), census_2024
        self.calls, self.responses, self.fail = [], {}, {}  # fail: url -> HTTP status, every time

    def __call__(self, url, headers, timeout):
        self.calls.append((url, dict(headers)))
        if url in self.fail:
            raise urllib.error.HTTPError(url, self.fail[url], "failing on purpose", {}, None)
        body, extra = None, {}
        if url == CENSUS_2023 or (url == CENSUS_2024 and self.census_2024):
            body = census_zip(url.rsplit("/", 1)[1][:-4])
        elif url == API + "published/filing":
            body = json.dumps({"data": FILINGS}).encode()
        elif url == API + "published/downloads":
            body = json.dumps({"data": DOWNLOADS}).encode()
        elif url == LISTING_URL:
            body = json.dumps({"data": self.listing}).encode()
        else:
            m = re.fullmatch(re.escape(API) + r"getNBMDataDownloadFile/(\d+)/1", url)
            if m and m.group(1) in self.files:
                name, body = self.files[m.group(1)]
                extra = {"Content-Length": str(len(body))}
                if name:
                    extra["Content-Disposition"] = f'attachment; filename="{name}"'
        if body is None:
            raise urllib.error.HTTPError(url, 404, "Not Found", {}, None)
        self.responses[url] = Resp(body, extra)
        return self.responses[url]

    def urls(self):
        return [u for u, _ in self.calls]

    def renumber(self, by):
        """What the FCC does when it re-processes a vintage: the same files under new ids, the old ids gone."""
        for r in self.listing:
            r["id"] += by
        self.files = {str(int(k) + by): v for k, v in self.files.items()}


class FakeResponse:
    """A requests response, as far as fcc_fetch uses one."""

    def __init__(self, status, headers, body):
        self.status_code, self.headers, self.body, self.closed = status, dict(headers), body, False
        self.reason = {200: "OK", 302: "Found", 404: "Not Found"}.get(status, "")
        self.is_redirect = status in (301, 302, 303, 307, 308) and "Location" in self.headers

    def iter_content(self, n):
        for i in range(0, len(self.body), 3):  # small, uneven pieces: the reader puts them back together
            yield self.body[i:i + 3]

    def close(self):
        self.closed = True


class FakeRequests:
    """A stand-in for the requests package: a Session whose answers are scripted, so no test needs requests itself."""

    def __init__(self, answers):
        self.answers, self.calls, self.responses, self.sessions = answers, [], [], 0
        fake = self

        class Session:
            def __init__(self):
                fake.sessions += 1

            def get(self, url, **kw):
                fake.calls.append((url, kw))
                fake.responses.append(FakeResponse(*fake.answers[url]))
                return fake.responses[-1]

        self.Session = Session


def client(net):
    return srv.FccClient(net, pause=0, sleep=lambda s: None)


class FccIsolated(unittest.TestCase):
    """Each test gets its own data folder, cache and settings."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        data = Path(self.tmp.name)
        patcher = mock.patch.multiple(srv, DATA=data, FCC_FILE=data / "fcc.json", FCC_CACHE=data / "fcc-cache",
                                      FCC_STATES="", FCC_MODE="")
        patcher.start()
        self.addCleanup(patcher.stop)
        srv._fcc_meta.clear()
        srv._fcc_http.clear()
        self.addCleanup(srv._fcc_http.clear)


class ReadersTest(FccIsolated):
    """The parts that need neither h3 nor a layer."""

    def test_dbf_reader_streams_live_records(self):
        table = dbf([("NAME", "C", 6), ("N", "N", 3), ("ID", "C", 4)],
                    [("ab", 1, "x1"), ("cd", 22, "x2"), ("ef", 333, "x3")], deleted={1})
        self.assertEqual([(0, ["x1", "1"]), (2, ["x3", "333"])], list(srv.read_dbf(io.BytesIO(table), ("id", "N"))))
        self.assertEqual([(2, ["x3", "ef"])], list(srv.read_dbf(io.BytesIO(table), ("ID", "name"), keep={b"x3"})))
        with self.assertRaises(ValueError):
            list(srv.read_dbf(io.BytesIO(table), ("missing",)))
        with self.assertRaises(ValueError):  # cut short
            list(srv.read_dbf(io.BytesIO(table[:-10]), ("ID",)))
        # A table bigger than one read: the record numbers carry on across the blocks.
        big = dbf([("PAD", "C", 250), ("K", "C", 5)], [("", f"k{i}") for i in range(10_000)])
        found = list(srv.read_dbf(io.BytesIO(big), ("K",), keep={b"k0", b"k4500", b"k9999"}))
        self.assertEqual([(0, ["k0"]), (4500, ["k4500"]), (9999, ["k9999"])], found)

    def test_states_by_even_odd_point_in_polygon(self):
        with zipfile.ZipFile(io.BytesIO(census_zip())) as z:
            shapes = srv.read_shp(z.read("cb_2024_us_state_20m.shp"))
        self.assertEqual([(-80, 40, -79, 41), (-79, 40, -78, 41)], [s[0] for s in shapes])
        self.assertEqual(2, len(shapes[0][1]))  # West: its outline and its lake
        states = [("81", "WS", *shapes[0]), ("82", "ES", *shapes[1])]
        self.assertEqual("81", srv.state_at(states, -79.8, 40.2))
        self.assertEqual("82", srv.state_at(states, -78.5, 40.5))
        self.assertIsNone(srv.state_at(states, -79.6, 40.7))  # in West's lake: a hole
        self.assertIsNone(srv.state_at(states, -77.0, 40.5))  # beyond East
        self.assertIsNone(srv.state_at(states, -79.5, 41.5))  # north of both
        with self.assertRaises(ValueError):
            srv.read_shp(b"PK not a shapefile" * 10)

    def test_a_state_counts_only_with_every_carriers_lte_file(self):
        files, missing = srv.fcc_pick(LISTING, "81")
        self.assertEqual(["101", "102", "103", "111", "112", "121", "122"], [f["id"] for f in files])
        self.assertEqual([], missing)
        self.assertEqual({"id": "103", "network": "verizon", "tier": 3, "technology": NR35}, files[2])
        self.assertEqual(["tmobile"], srv.fcc_pick(LISTING, "82")[1])
        self.assertEqual(([], ["verizon", "att", "tmobile"]), srv.fcc_pick(LISTING, "83"))

    def test_the_vintage_is_the_newest_biannual_filing(self):
        net = FakeNet({})
        self.assertEqual(("2025-12-31", UUID), srv.fcc_vintage(client(net)))
        self.assertEqual([API + "published/filing", API + "published/downloads"], net.urls())

    def test_client_paces_retries_and_sends_the_referer(self):
        sleeps, calls, answers = [], [], [429, ConnectionResetError(), 503]

        def fetch(url, headers, timeout):
            calls.append((url, headers))
            answer = answers.pop(0) if answers else None
            if isinstance(answer, Exception):
                raise answer
            if answer:
                raise urllib.error.HTTPError(url, answer, "busy", {}, None)
            return Resp(b'{"data": [1]}')

        c = srv.FccClient(fetch, pause=0, sleep=sleeps.append)
        self.assertEqual([1], c.data("published/filing"))
        self.assertEqual([2, 4, 8], sleeps)  # a 429, no answer at all, a 503: each tried again, later each time
        self.assertEqual("https://broadbandmap.fcc.gov/", calls[0][1]["Referer"])
        self.assertEqual("FamilyCoverage-server (+https://github.com/sstangle73/family-coverage)",
                         calls[0][1]["User-Agent"])
        answers[:] = [403]  # refused: not tried again
        with self.assertRaises(urllib.error.HTTPError):
            c.data("published/filing")
        answers[:], sleeps[:] = [500] * 5, []  # gives up after four retries
        with self.assertRaises(urllib.error.HTTPError):
            c.data("published/filing")
        self.assertEqual([2, 4, 8, 16], sleeps)
        calls.clear()
        c.get(CENSUS_2024).close()  # no Referer for the Census Bureau, and no other site at all
        self.assertNotIn("Referer", calls[0][1])
        with self.assertRaises(ValueError):
            c.get("https://example.com/where-we-went")
        paced, sleeps[:] = srv.FccClient(fetch, pause=0.5, sleep=sleeps.append), []
        paced.get(API + "a").close()
        paced.get(API + "b").close()
        self.assertEqual(1, len(sleeps))
        self.assertTrue(0.4 < sleeps[0] <= 0.5, sleeps)

    def test_off_by_default(self):
        env = {k: v for k, v in os.environ.items() if not k.startswith("FC_")}
        out = subprocess.run([sys.executable, "-c", "import familycoverage_server as s; "
                              "print(repr(s.FCC_MODE), s.start_fcc_builder())"],
                             cwd=os.path.dirname(os.path.abspath(__file__)), env=env, capture_output=True, text=True,
                             timeout=60)
        self.assertEqual("'' None", out.stdout.strip(), out.stderr)
        self.assertEqual("", out.stderr)  # not a word in the log either

    def test_fc_fcc_auto_starts_the_builder_or_says_why_not(self):
        with mock.patch.object(srv, "FCC_MODE", "sometimes"), contextlib.redirect_stderr(io.StringIO()) as err:
            self.assertIsNone(srv.start_fcc_builder())
        self.assertIn("FC_FCC=sometimes isn't a setting this server knows", err.getvalue())
        # Without h3, or without requests: one line naming what's missing, and the rest of the server runs as before.
        for gone in ("h3", "requests"):
            with mock.patch.object(srv, "FCC_MODE", "auto"), mock.patch.dict(sys.modules, {gone: None}), \
                    contextlib.redirect_stderr(io.StringIO()) as err:
                self.assertIsNone(srv.start_fcc_builder())
            self.assertEqual(1, len(err.getvalue().splitlines()))
            self.assertRegex(err.getvalue(), r"FC_FCC=auto, but the FCC layer needs the (h3 and requests packages|"
                                             + gone + r" package) \(pip install [a-z3 ]+\); everything else runs")
        if h3 is not None:
            ran = []
            with mock.patch.object(srv, "FCC_MODE", "auto"), contextlib.redirect_stderr(io.StringIO()) as err, \
                    mock.patch.dict(sys.modules, {"requests": types.ModuleType("requests")}), \
                    mock.patch.object(srv, "_fcc_loop", lambda: ran.append(1)):
                srv.start_fcc_builder().join(10)
            self.assertEqual([1], ran)
            self.assertIn("building the FCC layer in 60 s, then every 24 h", err.getvalue())

    def test_build_fcc_without_h3_says_so(self):
        net = FakeNet({})
        with mock.patch.dict(sys.modules, {"h3": None}), mock.patch.object(srv, "fcc_fetch", net), \
                self.assertRaises(SystemExit) as e:
            srv.cli(["x", "build-fcc"])
        self.assertIn("build failed, so fcc.json stays as it was: RuntimeError: the FCC layer needs the h3",
                      str(e.exception.code))
        self.assertEqual([], net.calls)

    def test_fcc_fetch_is_requests_kept_to_the_two_sites(self):
        body = json.dumps({"data": [{"x": 1}]}).encode()
        fake = FakeRequests({
            API + "a": (302, {"Location": "/nbm/map/api/b"}, b""),  # a redirect within the site: followed
            API + "b": (200, {"Content-Length": str(len(body))}, body),
            API + "c": (302, {"Location": "https://elsewhere.example/c"}, b""),  # off the two sites: refused
            API + "d": (404, {}, b"gone"),
            API + "e": (200, {"Content-Encoding": "gzip", "Content-Length": "5"}, b"0123456789"),
        })
        with mock.patch.dict(sys.modules, {"requests": fake}):
            c = srv.FccClient(pause=0, sleep=lambda s: None)  # the real fetch, on the stand-in for requests
            self.assertEqual([{"x": 1}], c.data("a"))
            with self.assertRaises(urllib.error.HTTPError) as e:
                c.get(API + "c")
            self.assertEqual(("https://elsewhere.example/c", 302), (e.exception.url, e.exception.code))
            with self.assertRaises(urllib.error.HTTPError) as e:
                c.get(API + "d")
            self.assertEqual(404, e.exception.code)
            with c.get(API + "e") as r:  # unpacked on the way: the compressed length can't check what's read
                self.assertIsNone(r.headers.get("Content-Length"))
                self.assertEqual([b"0123", b"4567", b"89", b""], [r.read(4) for _ in range(4)])
        self.assertEqual([API + u for u in "abcde"], [u for u, _ in fake.calls])
        self.assertEqual(1, fake.sessions)  # one session, kept
        self.assertTrue(all(r.closed for r in fake.responses))
        self.assertEqual({"headers": {"User-Agent": srv.FCC_USER_AGENT, "Accept": "application/json, */*",
                                      "Referer": "https://broadbandmap.fcc.gov/"},
                          "timeout": 120, "stream": True, "allow_redirects": False}, fake.calls[0][1])
        # Without requests: one clear error, not tried again.
        srv._fcc_http.clear()
        tries = []
        with mock.patch.dict(sys.modules, {"requests": None}), self.assertRaises(RuntimeError) as e:
            srv.FccClient(pause=0, sleep=tries.append).get(API + "published/filing")
        self.assertEqual("the FCC layer needs the requests package (pip install requests)", str(e.exception))
        self.assertEqual([], tries)


@unittest.skipIf(h3 is None, "needs the h3 package")
class BuildTest(FccIsolated):
    """Whole builds against the fake FCC."""

    def setUp(self):
        super().setUp()
        cell = {k: h3.latlng_to_cell(lat, lon, 9) for k, (lat, lon) in {
            "A": (40.2, -79.8), "B": (40.85, -79.2), "C": (40.35, -79.45), "D": (40.2, -79.3),  # West
            "H": (40.7, -79.6),  # West's lake: in no state
            "E": (40.5, -78.5),  # East
            "X": (40.5, -77.0),  # beyond East: in no state
            "far": (40.05, -79.95),  # in West's files, but no reading there
        }.items()}
        self.parent = h3.cell_to_parent(cell["D"], 8)
        self.kids = sorted(h3.cell_to_children(self.parent, 9))  # all seven: compacted to their parent
        self.A, self.B, self.C = cell["A"], cell["B"], cell["C"]

        def at(c, acc="10"):
            lat, lon = h3.cell_to_latlng(c)
            return sample(lat, lon, acc)

        def sample(lat, lon, acc="10", end="\n"):
            r = dict.fromkeys(SAMPLES, "")
            r.update(ts="2026-10-01T08:00:00.000-04:00", member="Sam", sub_id="1", sub_label="SIM 1",
                     carrier="Bluewave", mcc_mnc="311480", service_state="IN_SERVICE", cell_service="IN_SERVICE",
                     rat="LTE", lat=str(lat), lon=str(lon), accuracy_m=acc, mode="STILL")
            return ",".join(r[c] for c in SAMPLES) + end

        sam = srv.DATA / "sam" / "0123456789abcdef"
        sam.mkdir(parents=True)
        (sam / "samples-2026-10-01.csv").write_text(
            ",".join(SAMPLES) + "\n" + at(self.A) + at(self.A) + at(self.B, "150") + at(self.C, "")
            + "".join(at(k) for k in self.kids) + at(cell["H"]) + at(cell["X"])
            + sample("", "")              # no fix
            + sample(40.9, -79.9, "150.5")  # too vague for the report's map
            + sample(40.1, -79.1, end=""),  # a row still arriving
            encoding="utf-8", newline="")
        (sam / "track-2026-10-01.csv").write_text("ts,member,lat,lon,accuracy_m\nx,Sam,40.95,-79.05,5\n")
        jo = srv.DATA / "jo" / "fedcba9876543210"
        jo.mkdir(parents=True)
        (jo / "samples-2026-10-02.csv").write_text(",".join(SAMPLES) + "\n" + at(cell["E"]), encoding="utf-8")
        A, B, C = self.A, self.B, self.C
        self.files = {
            "101": ("bdc_81_131425_4GLTE_mobile_broadband_h3_D25_04aug2026.zip",
                    h3_zip("vz_lte", [(A, 1), (B, 0), (C, 1), (cell["far"], 1)] + [(k, 1) for k in self.kids])),
            "102": ("bdc_81_131425_5GNR_7_1_mobile_broadband_h3_D25_04aug2026.zip", h3_zip("vz_7", [(A, 0)])),
            "103": ("bdc_81_131425_5GNR_35_3_mobile_broadband_h3_D25_04aug2026.zip",
                    h3_zip("vz_35", [(A, 1), (B, 1)], deleted={0})),  # A's row is deleted: it doesn't count
            "111": ("bdc_81_130077_4GLTE_mobile_broadband_h3_D25_23jun2026.zip", h3_zip("att_lte", [(A, 0), (C, 0)])),
            "112": ("../../escape.zip", h3_zip("att_7", [])),  # a name that would leave the folder: the id instead
            "121": ("bdc_81_130403_4GLTE_mobile_broadband_h3_D25_04may2026.zip",
                    h3_zip("tmo_lte", [(A, 1), (B, 1), (C.upper(), 1)])),
            "122": (None, h3_zip("tmo_7", [(B, 1)])),  # no Content-Disposition: named by its id
        }

    def test_build_writes_the_layer_in_the_documented_shape(self):
        net = FakeNet(self.files, census_2024=False)  # 2024's outlines not out yet: 2023's
        with contextlib.redirect_stderr(io.StringIO()) as err:
            s = srv.build_fcc(client(net))
        layer = json.loads(srv.FCC_FILE.read_text(encoding="utf-8"))
        A, B, C, D = self.A, self.B, self.C, self.parent
        self.assertEqual(["format", "version", "vintage", "updated", "source", "states", "networks", "domain"],
                         list(layer))
        self.assertEqual(("family-coverage-fcc", 1, "2025-12-31", ["81"]),
                         (layer["format"], layer["version"], layer["vintage"], layer["states"]))
        self.assertEqual("FCC National Broadband Map, Broadband Data Collection mobile coverage (providers' filings)",
                         layer["source"])
        self.assertIsNotNone(datetime.fromisoformat(layer["updated"]).tzinfo)
        self.assertEqual(sorted([A, B, C, D]), layer["domain"])  # West's hexagons, the seven siblings as one
        nets = layer["networks"]
        self.assertEqual(["verizon", "att", "tmobile"], list(nets))
        self.assertEqual({"label": "Verizon", "plmns": (
            "311480 310004 310012 311270 311271 311272 311273 311274 311275 311276 311277 311278 311279 311280 "
            "311281 311282 311283 311284 311285 311286 311287 311288 311289 311390").split(),
            "o": {"1": sorted([C, D]), "2": [A], "3": [B]},
            "v": {"1": sorted([A, C, D]), "2": [], "3": [B]}}, nets["verizon"])
        self.assertEqual({"label": "AT&T", "plmns": "310410 310150 310170 310280 310380 310560 310680 311180 313100 "
                                                    "310030".split(),
                          "o": {"1": sorted([A, C]), "2": [], "3": []}, "v": {"1": [], "2": [], "3": []}}, nets["att"])
        self.assertEqual({"label": "T-Mobile", "plmns": (
            "310260 310160 310200 310210 310220 310230 310240 310250 310270 310310 310490 310660 310800 312250 "
            "311490 311660 311882 312530").split(),
            "o": {"1": sorted([A, C]), "2": [B], "3": []},
            "v": {"1": sorted([A, C]), "2": [B], "3": []}}, nets["tmobile"])
        # A, B, C and the seven siblings are in West; E's state (East) lacks T-Mobile's LTE file; the lake's and X's
        # hexagons are in no state. The vague fix, the row without one, the row still arriving and the track's point
        # aren't hexagons at all.
        self.assertEqual({"cells": 13, "domain": 10, "in_skipped": 1, "outside": 2, "states": ["WS"],
                          "skipped": {"ES": "no 4G LTE file for T-Mobile"}, "vintage": "2025-12-31", "written": True},
                         {k: s[k] for k in ("cells", "domain", "in_skipped", "outside", "states", "skipped", "vintage",
                                            "written")})
        # Downloads: the Census file (2023's after 2024's 404), the vintage, the list, West's seven files, and nothing
        # else. Only from the two sites, never a point lookup, with the Referer the FCC wants.
        self.assertEqual([CENSUS_2024, CENSUS_2023, API + "published/filing", API + "published/downloads", LISTING_URL]
                         + [API + f"getNBMDataDownloadFile/{i}/1" for i in (101, 102, 103, 111, 112, 121, 122)],
                         net.urls())
        self.assertTrue(all(h["Referer"] == "https://broadbandmap.fcc.gov/" for u, h in net.calls if "fcc.gov" in u))
        self.assertEqual(set(srv.FCC_HOSTS), {urllib.parse.urlsplit(u).hostname for u in net.urls()})
        # The cache: the outlines, the vintage's list, and West's files with their list, written last.
        cache = srv.FCC_CACHE
        self.assertTrue((cache / "cb_2023_us_state_20m.zip").is_file())
        self.assertTrue((cache / "2025-12-31" / "listing.json").is_file())
        self.assertFalse((cache / "2025-12-31" / "82").exists())
        west = cache / "2025-12-31" / "81"
        done = json.loads((west / "done.json").read_text())
        named = [self.files[i][0] for i in ("101", "102", "103", "111", "121")]
        self.assertEqual(sorted(["112.zip", "122.zip"] + named), sorted(f["file"] for f in done["files"]))
        self.assertEqual({"id": "112", "network": "att", "tier": 2, "technology": NR7, "file": "112.zip",
                          "bytes": len(self.files["112"][1])}, done["files"][4])
        self.assertEqual(sorted(f["file"] for f in done["files"]) + ["done.json"],  # no .part left behind
                         sorted(p.name for p in west.iterdir()))
        self.assertIn("fcc: WS: 7 files, ", err.getvalue())  # each state's total, after each file's size
        self.assertIn("fcc: WS Verizon 4G LTE: bdc_81_131425_4GLTE_mobile_broadband_h3_D25_04aug2026.zip, ",
                      err.getvalue())
        self.assertEqual("fcc: built fcc.json (%s) in %d s: vintage 2025-12-31; data for WS; skipped ES (no 4G LTE "
                         "file for T-Mobile); hexagons with readings 13: in the layer 10, in skipped states 1, in no "
                         "state 2" % (srv.fmt_size(s["bytes"]), s["seconds"]), srv.fcc_summary(s))

    def test_a_failed_build_keeps_the_old_layer_and_the_next_one_carries_on(self):
        srv.FCC_FILE.write_text('{"format": "family-coverage-fcc", "old": true}')
        net = FakeNet(self.files)
        stuck = API + "getNBMDataDownloadFile/112/1"
        net.fail[stuck] = 503
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(urllib.error.HTTPError):
            srv.build_fcc(client(net))
        self.assertIn('"old": true', srv.FCC_FILE.read_text())  # the previous layer stays
        west = srv.FCC_CACHE / "2025-12-31" / "81"
        self.assertFalse((west / "done.json").exists())
        self.assertEqual(5, net.urls().count(stuck))  # once, and four retries
        self.assertEqual(4, len(list(west.glob("bdc_*.zip"))))
        # The next build uses the saved list, and the files already here aren't fetched again.
        del net.fail[stuck]
        net.calls.clear()
        with contextlib.redirect_stderr(io.StringIO()) as err:
            srv.build_fcc(client(net))
        self.assertNotIn(LISTING_URL, net.urls())
        reads = {i: net.responses[API + f"getNBMDataDownloadFile/{i}/1"].reads for i in self.files}
        self.assertEqual({"101": 0, "102": 0, "103": 0, "111": 0}, {i: reads[i] for i in ("101", "102", "103", "111")})
        self.assertTrue(all(reads[i] for i in ("112", "121", "122")))
        self.assertEqual(4, err.getvalue().count(", already here"))
        self.assertTrue((west / "done.json").exists())
        self.assertNotIn('"old"', srv.FCC_FILE.read_text())
        # Once the state is done, a build asks only for the vintage.
        net.calls.clear()
        srv.build_fcc(client(net))
        self.assertEqual([API + "published/filing", API + "published/downloads"], net.urls())

    def test_renumbered_files_fetch_the_list_again(self):
        net = FakeNet(self.files)
        with contextlib.redirect_stderr(io.StringIO()):
            srv.build_fcc(client(net))
        shutil.rmtree(srv.FCC_CACHE / "2025-12-31" / "81")  # say the family's first trip there is only now
        net.renumber(1000)
        net.calls.clear()
        with contextlib.redirect_stderr(io.StringIO()) as err:
            s = srv.build_fcc(client(net))
        self.assertTrue(s["written"])
        self.assertEqual(1, net.urls().count(LISTING_URL))
        self.assertIn("fetched again", err.getvalue())
        self.assertIn(API + "getNBMDataDownloadFile/1101/1", net.urls())

    def test_fc_fcc_states_limits_the_downloads(self):
        net = FakeNet(self.files)
        with mock.patch.object(srv, "FCC_STATES", "es, 99"), contextlib.redirect_stderr(io.StringIO()) as err:
            s = srv.build_fcc(client(net))
        self.assertIn("FC_FCC_STATES: 99 isn't a state", err.getvalue())
        self.assertFalse(s["written"])  # East is allowed but lacks T-Mobile's LTE file; West isn't allowed
        self.assertEqual({"ES": "no 4G LTE file for T-Mobile", "WS": "not in FC_FCC_STATES"}, s["skipped"])
        self.assertFalse(any("getNBMDataDownloadFile" in u for u in net.urls()))
        self.assertFalse(srv.FCC_FILE.exists())
        self.assertIn("left as it was, as no hexagon with readings is in a state with the FCC's files",
                      srv.fcc_summary(s))
        with mock.patch.object(srv, "FCC_STATES", "81"), contextlib.redirect_stderr(io.StringIO()):
            s = srv.build_fcc(client(net))
        self.assertEqual((["WS"], {"ES": "not in FC_FCC_STATES"}), (s["states"], s["skipped"]))

    def test_no_readings_means_nothing_goes_online(self):
        shutil.rmtree(srv.DATA / "sam")
        shutil.rmtree(srv.DATA / "jo")
        net = FakeNet(self.files)
        s = srv.build_fcc(client(net))
        self.assertEqual([], net.calls)
        self.assertFalse(s["written"])
        self.assertEqual("fcc: nothing to build, as there are no readings with a position yet", srv.fcc_summary(s))

    def test_build_fcc_command(self):
        net = FakeNet(self.files)
        with mock.patch.object(srv, "fcc_fetch", net), mock.patch.object(srv, "FCC_PAUSE", 0), \
                contextlib.redirect_stdout(io.StringIO()) as out, contextlib.redirect_stderr(io.StringIO()):
            srv.cli(["x", "build-fcc"])
        lines = out.getvalue().splitlines()
        self.assertTrue(lines[0].startswith("fcc: built fcc.json ("), lines)
        self.assertIn("; data for WS; skipped ES (no 4G LTE file for T-Mobile);", lines[0])
        self.assertEqual(
            ["  Verizon: outdoors 10 of 10 claimed (5G 35/3 Mbps 1, 5G 7/1 Mbps 1, LTE 8), in a moving car 10",
             "  AT&T: outdoors 2 of 10 claimed (5G 35/3 Mbps 0, 5G 7/1 Mbps 0, LTE 2), in a moving car 0",
             "  T-Mobile: outdoors 3 of 10 claimed (5G 35/3 Mbps 0, 5G 7/1 Mbps 1, LTE 2), in a moving car 3"],
            lines[1:])
        # A failure: one line, exit status 1, and the layer stays as it was.
        before = srv.FCC_FILE.read_bytes()
        net.fail[API + "published/filing"] = 403
        with mock.patch.object(srv, "fcc_fetch", net), mock.patch.object(srv, "FCC_PAUSE", 0), \
                self.assertRaises(SystemExit) as e:
            srv.cli(["x", "build-fcc"])
        self.assertEqual("fcc: build failed, so fcc.json stays as it was: HTTPError: HTTP Error 403: failing on "
                         f"purpose ({API}published/filing)", e.exception.code)
        self.assertEqual(before, srv.FCC_FILE.read_bytes())

    def test_metrics_show_the_layers_age_and_states(self):
        self.assertNotIn("familycoverage_fcc", srv.metrics_text())
        with contextlib.redirect_stderr(io.StringIO()):
            srv.build_fcc(client(FakeNet(self.files)))
        text = srv.metrics_text()
        self.assertIn("# TYPE familycoverage_fcc_layer_built_timestamp_seconds gauge", text)
        self.assertIn(f"familycoverage_fcc_layer_built_timestamp_seconds {int(srv.FCC_FILE.stat().st_mtime)}\n", text)
        self.assertIn("familycoverage_fcc_layer_states 1\n", text)

    def test_a_damaged_file_is_dropped_for_the_next_build(self):
        with contextlib.redirect_stderr(io.StringIO()):
            srv.build_fcc(client(FakeNet(self.files)))
        west = srv.FCC_CACHE / "2025-12-31" / "81"
        path = west / self.files["101"][0]
        with zipfile.ZipFile(path) as z:
            info = z.getinfo("vz_lte.dbf")
        data = bytearray(path.read_bytes())
        data[info.header_offset + 30 + len(info.filename) + info.compress_size // 2] ^= 0xFF  # inside the table
        path.write_bytes(bytes(data))
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(ValueError) as e:
            srv.build_fcc(client(FakeNet(self.files)))
        self.assertIn("is damaged", str(e.exception))
        self.assertFalse(path.exists())
        self.assertFalse((west / "done.json").exists())


if __name__ == "__main__":
    unittest.main()
