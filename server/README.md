# Family Coverage server (optional)

Most households don't need this: each phone keeps its own data and exports it. Run a server if you'd like the
phones to copy their data somewhere automatically, to **watch the results as they come in**, or to measure whether
your home server is reachable from where your family goes.

It's one Python file with no dependencies beyond the standard library, except for the optional FCC comparison, which
also needs the h3 and requests packages (the Docker image has them).

## What it does

- **Takes uploads, append-only.** Each request says "file X from byte N"; the server appends what it doesn't have
  and answers with its size. A lost answer costs nothing.
- **Approves installs by hand.** A new install registers as *pending* and uploads nothing until you approve it,
  after checking its device id on the phone. The server stores only a hash of each install's key.
- **Serves the report live**, at `/report/`, behind a password you set.
- **Says what it is:** `GET /api/hello?household=<id>` answers `{"app": "family-coverage", "api": 1, "household":
  "ok" | "any" | "other"}` without a key, so the app can check an address when a household is set up. It never names
  the household it belongs to.
- **Answers the server test:** `/api/test/ping`, a download (`/api/test/down?bytes=N`, at most 2 MB) and an upload
  (at most 1 MB), for approved installs only. `POST /api/test/begin` and `/api/test/end` answer with how the
  server reaches the phone: through Tailscale, whether `tailscale status` shows a direct path or a relay.
- **Writes the same zips the phones export** (`export-zips`), so the report page opens them unchanged.
- **Serves a read-only export API** with its own token, plus `/healthz` and Prometheus `/metrics` for private
  networks only.
- **Builds the FCC comparison**, if you turn it on: what each carrier claims to the FCC, in the hexagons the phones
  have been in, for the live report's map.

## Run it

With Docker:

```bash
cd server
docker compose up -d --build
```

Or straight from Python 3.10 or newer, from the repository root:

```bash
FC_DATA=./data python3 server/familycoverage_server.py serve
```

Then, in the household's settings on the first phone, set the server to its address. Use `https://` if it's
reachable from the internet (put it behind a reverse proxy with a certificate). Plain `http://` works only on your
home network or a private VPN such as Tailscale: the app refuses to send data over plain http to anything else.

## Approve each phone

Once a household has a server, each phone's status (under *Start recording*) shows a line like
`Server: household 0a1b2c3d, device 3f9c0e41d2a7b655 (pending)`. On the server:

```bash
docker compose exec familycoverage python /app/familycoverage_server.py list
docker compose exec familycoverage python /app/familycoverage_server.py approve <device_id>
```

`revoke <device_id>` stops an install uploading. Set `FC_HOUSEHOLD` to your household's id (from that status line) so
other households can't even register. At most 20 installs can wait for approval at once.

## Watch it live

Set the report's password once (12 characters or more; the server keeps only a scrypt hash of it):

```bash
docker compose exec -it familycoverage python /app/familycoverage_server.py set-report-password
```

Then open `http://<the server>:8745/report/` (or your https name) and enter it. The page shows every phone's latest
uploads, compared network by network, and checks for new ones every 5 minutes while it's open. "Live" is as fresh as
the uploads: the phones send theirs every 15 minutes on Wi-Fi and every hour elsewhere.

- The unlock lasts 12 hours, for that browser tab only.
- Five wrong passwords in 15 minutes lock logins for 15 minutes.
- The page reads each phone as the same zip it would export, and fetches a phone again only after it has uploaded.
- `set-report-password --stdin` reads the password from standard input instead, for a script.

### The FCC's map beside yours

The live report's map can show what each carrier claims to the FCC next to what the phones measured. Browsers can't
fetch the FCC's data from another site, so the server hands it over: the logged-in report reads `data/fcc.json` from
`/api/report/fcc`. The server can build that file itself, or you can put one there (the format is in
`docs/data-format.md`).

Set `FC_FCC=auto`, and about a minute after it starts, then every 24 hours, the server:

1. finds every hexagon (H3 resolution 9, the report's grid) with a stored reading whose fix is within 150 m;
2. finds each hexagon's state, from the Census Bureau's outlines of the states (downloaded once, 0.2 MB);
3. finds the FCC's newest data (its Broadband Data Collection, published twice a year) and, for each of those states,
   downloads Verizon's, AT&T's and T-Mobile's H3 coverage files once: 4G LTE, 5G at 7/1 Mbps and 5G at 35/3 Mbps;
4. reads each file's table, keeps the rows for those hexagons, and writes `fcc.json`: each carrier's best tier in
   each hexagon, outdoors and in a moving car.

Or build it once, for instance from cron instead of on the server's own schedule:

```bash
docker compose exec familycoverage python /app/familycoverage_server.py build-fcc
```

- **Privacy:** only whole-state files are downloaded, so the family's positions never leave the server; the FCC sees
  only which states' files it sent. The server never uses the FCC map's lookups for a single place, and talks only
  to `broadbandmap.fcc.gov` and `www2.census.gov`.
- **Only complete states:** a state is used only if all three carriers have their 4G LTE file there, as a missing
  file would otherwise read as "claims nothing". `FC_FCC_STATES` (such as `MA,VT`, or FIPS codes) limits the
  downloads to those states. Hexagons in a state left out, or in none (abroad, offshore), have no FCC data.
- **Disk:** about 1 MB for Washington DC, 43 MB for Vermont and 365 MB for New York, kept in `data/fcc-cache/` so
  each file is downloaded once, plus 4 MB for the FCC's list of files. A new vintage goes into a new folder; delete
  the old one when you like.
- **Time:** the first build takes as long as its downloads (12 seconds in all for Washington DC). After that a build
  reads what's cached, in a second or two, and downloads only a state the family has newly been to, or a new
  vintage. An interrupted download carries on next time.
- **Logs:** each build logs one line: the vintage, the states used and skipped (and why), the hexagons and the
  file's size. A build that fails (the FCC's site down, say) logs one line and leaves the last `fcc.json` in place.
  `/metrics` has `familycoverage_fcc_layer_built_timestamp_seconds` and `familycoverage_fcc_layer_states`, for an
  alert if the layer stops being rebuilt.
- **State lines:** the outlines are simplified (1:20 million), so a hexagon right on a coast can fall outside every
  state, and one near a state line can be counted in the neighbour. When the family has readings in both states,
  each hexagon is looked up in both states' files, so this matters only at the edges of where they've been.
- **Packages:** the Docker image has the two this needs: h3, for the hexagons, and requests, for the downloads (the
  FCC's site turns away Python's own urllib; requests goes as itself, a program saying who it is, never as a
  browser). Run from Python, `pip install h3 requests`; without them, the server says so once and does everything
  else as usual.

## Get the data out

```bash
docker compose exec familycoverage python /app/familycoverage_server.py export-zips /data/exports
```

That writes one zip per install into `data/exports/`. Open them in the report page.

The export API (`GET /api/export/` for a listing with sizes and sha256, then `GET /api/export/<member>/<device>/<file>`,
which honours `Range: bytes=N-`) needs the token that `new-export-token` writes to `data/export_token`.

## Settings

| Variable | Default | |
|---|---|---|
| `FC_DATA` | `/data` | where everything is stored |
| `FC_LISTEN` | `0.0.0.0:8745` | address and port |
| `FC_HOUSEHOLD` | (any) | the only household id allowed to register |
| `FC_API_ALLOW` | everyone | CIDRs allowed to use `/api/*` and `/report/` |
| `FC_OPEN_ALLOW` | private networks | CIDRs allowed to read `/healthz` and `/metrics` |
| `FC_EXPORT_TOKEN_FILE` | `<FC_DATA>/export_token` | the export API's token |
| `FC_REPORT_PASSWORD_FILE` | `<FC_DATA>/report_password` | the report password's scrypt hash |
| `FC_REPORT_HTML` | `report.html` beside the server, else `docs/report/index.html` | the report page |
| `FC_TAILSCALE` | `tailscale` | the Tailscale CLI, for the server test's path |
| `FC_FCC_FILE` | `<FC_DATA>/fcc.json` | the carriers' claimed coverage for the report's map, if present |
| `FC_FCC` | off | `auto`: build `FC_FCC_FILE` a minute after startup, then every 24 hours |
| `FC_FCC_STATES` | every state with readings | only these states' FCC files, such as `MA,VT` (or FIPS codes) |
| `FC_FCC_CACHE` | `<FC_DATA>/fcc-cache` | where the downloaded FCC and Census files are kept |

### On Tailscale

When the server runs on a Tailscale node and the phones reach it at its Tailscale address (or MagicDNS name), each
server test records whether the phone's path was direct or relayed. The server runs `tailscale status --json`, so
in Docker give the container the host's CLI and its socket (the two commented lines in `compose.yaml`). Without
them the path is `unknown`; tests that don't come through Tailscale say `not_tailscale`.

## Files

```
data/devices.json                         installs: member, key hash, status, model, app version, consent time
data/report_password                      the report password's scrypt hash (mode 600)
data/<member>/<device_id>/<table>-<date>.csv
data/<member>/<device_id>/manifest.json   the household and places, as the phone last sent them
data/fcc.json                             the FCC layer for the report's map (FC_FCC, build-fcc, or your own)
data/fcc-cache/cb_2024_us_state_20m.zip   the Census Bureau's outlines of the states
data/fcc-cache/<vintage>/listing.json     the FCC's list of files for that vintage
data/fcc-cache/<vintage>/<FIPS>/          one state's H3 files, and done.json once they've all arrived
```

The data is location history. Back it up like any other private data, and delete it when the household has
decided.

## Tests

```bash
python3 -m unittest discover -s server -p "test_*.py"
```
