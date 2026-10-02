# Family Coverage server (optional)

Most households don't need this: each phone keeps its own data and exports it. Run a server if you'd like the
phones to copy their data somewhere automatically, or to measure whether your home server is reachable from where
your family goes.

It's one Python file with no dependencies beyond the standard library.

## What it does

- **Takes uploads, append-only.** Each request says "file X from byte N"; the server appends what it doesn't have
  and answers with its size. A lost answer costs nothing.
- **Approves installs by hand.** A new install registers as *pending* and uploads nothing until you approve it,
  after checking its device id on the phone. The server stores only a hash of each install's key.
- **Answers the server test:** `/api/test/ping`, a download (`/api/test/down?bytes=N`, at most 2 MB) and an upload
  (at most 1 MB), for approved installs only.
- **Writes the same zips the phones export** (`export-zips`), so the report page opens them unchanged.
- **Serves a read-only export API** with its own token, plus `/healthz` and Prometheus `/metrics` for private
  networks only.

## Run it

With Docker:

```bash
cd server
docker compose up -d --build
```

Or straight from Python 3.10 or newer:

```bash
FC_DATA=./data python3 familycoverage_server.py serve
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
| `FC_API_ALLOW` | everyone | CIDRs allowed to use `/api/*` |
| `FC_OPEN_ALLOW` | private networks | CIDRs allowed to read `/healthz` and `/metrics` |
| `FC_EXPORT_TOKEN_FILE` | `<FC_DATA>/export_token` | the export API's token |

## Files

```
data/devices.json                         installs: member, key hash, status, model, app version, consent time
data/<member>/<device_id>/<table>-<date>.csv
data/<member>/<device_id>/manifest.json   the household and places, as the phone last sent them
```

The data is location history. Back it up like any other private data, and delete it when the household has
decided.

## Tests

```bash
python3 -m unittest discover -s server -p "test_*.py"
```
