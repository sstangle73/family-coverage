# The data format

Each phone's export is one zip:

```
manifest.json                     who, which household, its places, the app version
csv/<table>-<YYYY-MM-DD>.csv      one file per table per day, in the phone's local time
```

Every CSV has a header row. Times (`ts`) are RFC 3339 with the phone's offset and millisecond precision, such as
`2026-10-01T08:30:00.412-04:00`. Empty means unknown. Columns are only ever **added at the end**; a file started by an
older version keeps its own header.

The first two columns are always `ts` and `member` (the household member the phone belongs to).

## manifest.json

```json
{
  "format": "family-coverage-export", "version": 1, "app_version": "0.1.0", "flavor": "full",
  "member": "Sam", "device_id": "3f9c0e41d2a7b655", "exported_at": "...",
  "household": { "id": "0a1b2c3d", "name": "The Smiths", "members": ["Alex", "Sam"], "end": "2026-11-15",
                 "server": "https://coverage.example.com",
                 "places": [{ "id": "home", "name": "Home", "lat": 42.36, "lon": -71.05, "r": 150 }] },
  "tables": { "samples": ["ts", "member", "..."] },
  "files": ["csv/samples-2026-10-01.csv"]
}
```

`device_id` names the install (a new install is a new id). `server` is absent when the household has none.

## samples: each SIM's state

One row per SIM per reading: every 10 seconds while moving, about every 2 minutes while still.

| Column | Meaning |
|---|---|
| `sub_id`, `sub_label` | Android's id for the SIM, and its label in Settings |
| `carrier`, `mcc_mnc` | The SIM's carrier, and the network it's registered on (MCC+MNC) |
| `data_sim` | `true` on the SIM carrying mobile data |
| `service_state` | `IN_SERVICE`, `OUT_OF_SERVICE`, `EMERGENCY_ONLY`, or `POWER_OFF` (airplane mode, not a dead spot) |
| `voice_transport` | How a call would go: `LTE`/`NR` (VoLTE/VoNR), `IWLAN` (Wi-Fi or cross-SIM calling), `CS`, `NONE` |
| `roaming`, `wifi_connected` | |
| `rat` | `NR_SA` (5G standalone), `NR_NSA` (LTE with a 5G leg), `LTE`, `UMTS`, `NONE` |
| `band`, `arfcn`, `pci`, `cell_id`, `tac` | The serving cell |
| `rsrp`, `rsrq`, `sinr` | Signal (dBm, dB, dB): RSRP −80 is strong, −110 weak, −120 barely usable |
| `nr_band` … `nr_sinr` | The 5G leg, on NR_NSA |
| `cell_age_s` | How old the modem's cell reading was |
| `lat`, `lon`, `accuracy_m`, `altitude_m`, `speed_mps`, `fix_age_s` | The location. While still, the best fix of the still period |
| `display_override` | The status bar's extra icon: `NR_NSA` (5G), `NR_ADVANCED` (5G+/UW/UC), `LTE_CA`, … |
| `cc_count`, `bw_mhz` | Serving carriers and their total bandwidth |

## track: the GPS track

`lat`, `lon`, `accuracy_m`, `altitude_m`, `speed_mps`, `bearing_deg`, `provider`: a point at least every 10 m or
every minute while moving.

## checks: does mobile data work?

Off Wi-Fi, every 2 minutes on the move and 10 minutes still: one tiny request to Cloudflare's 1.1.1.1.
`result` is `OK`, `TIMEOUT`, `CONNECT_FAIL`, `NO_DATA_NETWORK`, `HTTP_ERROR` (something other than Cloudflare
answered, such as a carrier's top-up page) or `ERROR`. `connect_ms` and `total_ms` time it; `colo` is the
Cloudflare site that answered. `test_path` is `default`, or `exit_node` when a VPN carried it.

## tests: speed tests

Every 30 minutes moving, 60 still, over cellular even on Wi-Fi: latency, 1 MB down and 250 KB up against
`speed.cloudflare.com`. `result` adds `DNS_FAIL`, `NO_DATA_NETWORK` and `VPN_BLOCKED` (a VPN refused the
cellular-bound test: says nothing about the network). `egress_asn`/`egress_org` name the network the test left
through. `test_path` is `cellular_bound`, `default` or `exit_node`.

## server: reaching your own server

Only with a server set, off Wi-Fi, hourly moving and every 3 hours still: latency, 500 KB down, 125 KB up.
`result` adds `NOT_APPROVED` (approve the install on the server) and `REFUSED` (plain http to a public address).

## usage: mobile data

One row per 2 minutes, and whenever the data SIM changes: bytes on the data SIM (`rx_bytes`, `tx_bytes`), of
which this app's own tests (`app_*`), and Android's total over every cellular interface (`all_*`, a cross-check).

## events: call and text taps

One row per SIM per tap: `kind` (`CALL_IN`, `TEXT_IN`, or `UNDO`, which cancels the tap before it), `outcome`
(`REACHED`, `MISSED`), `target_sub_id` (the SIM that was called), and each SIM's state at that moment.

## texts: test texts

One row per message event per phone. `event`: `sent`, `delivered` (the network's delivery report), `received`,
or `composed` (the Google Play build: Messages was opened with the test). `test_id` pairs the two phones' rows;
`exchange_id` groups one exchange's texts; `role` is `test` or `echo` (the reply). `transport`: `data` (silent),
`text` (visible) or `manual`. `peer` is the partner's household name. `result` on `sent` rows is the send result
(`OK`, `NO_SERVICE`, `NETWORK_ERROR`, …; `APP_ERROR` means the phone refused it), on `delivered` rows `DELIVERED`,
`PENDING` or `FAILED`. `latency_s` is from the time written in the text to its arrival (the two phones' clocks);
`rtt_s` from sending a test to its reply.

## heartbeat

Every 2 minutes: `logger_state` (`RUNNING`, `PAUSED`, `ENDED`), `battery_pct`, `charging`, `app_version`.
