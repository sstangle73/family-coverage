# Running your own server

Optional. Without a server, each phone keeps its data until you export it, and that's enough to decide.

A server suits a household that:
- would like to **watch the results as they come in**, without exporting from every phone;
- or wants to know whether a home server (cameras, files, a media server) is usable from where the family goes: the
  app tests reaching it hourly.

It's a single Python file, standard library only (the optional FCC comparison also needs two packages), with a
Docker image. In short:

1. Run it at home (`docker compose up -d --build` in the `server` folder of the source code).
2. Make it reachable: on your home network or a private VPN such as Tailscale over plain http, or from anywhere
   over https behind a reverse proxy with a certificate.
3. In the app, **Edit the household** on one phone and set the server's address. Share the household again, and
   scan the updated code on the other phones. Each person agrees again, because the data now goes somewhere new.
4. Approve each phone on the server (`approve <device_id>`). The id is in each phone's status.
5. Set a report password (`set-report-password`), then open `/report/` on the server: the same report page, live.
   It's as fresh as the phones' uploads, which come every 15 minutes on Wi-Fi and every hour elsewhere. In the app,
   **Open the live report** goes there too, once the household has a server.
6. At the end, `export-zips` writes the same zips the phones export.

On Tailscale, a server that is itself a Tailscale node also records whether each phone reached it directly or
through Tailscale's relays, which is what makes a camera stream smooth or choppy.

## The FCC comparison

The live report's map can show what each carrier claims to the FCC (the National Broadband Map) beside what your
phones measured. The server can build that layer itself:

- **Turn it on** with the setting `FC_FCC=auto`. About a minute after the server starts, and then every 24 hours, it
  downloads the FCC's coverage files for Verizon, AT&T and T-Mobile in each state where your phones have readings,
  and keeps just the hexagons they've been in. `FC_FCC_STATES` (for example `MA,VT`) limits it to those states. To
  build on your own schedule instead, run `build-fcc` from cron.
- **Privacy.** It downloads only whole-state files, so your positions never leave the server: the FCC learns which
  states' files were downloaded, nothing finer. It never asks the FCC's map about a single place. It contacts only
  the FCC's map (broadbandmap.fcc.gov) and the Census Bureau (www2.census.gov, for the outlines of the states).
- **Disk space.** The files stay in `fcc-cache` in the server's data folder, so each is downloaded once: about 1 MB
  for Washington DC, 43 MB for Vermont and 365 MB for New York, plus 4 MB for the FCC's list of files. The FCC
  publishes new data twice a year; the new files go beside the old ones, whose folder you can then delete.
- **Time.** The first build takes as long as those downloads do on your connection: for Washington DC, 12 seconds
  in all. After that, a build downloads only what's new (a state your family has just been to, or new data from the
  FCC), and takes seconds.

A state is used only when all three carriers have their 4G LTE file there, so a missing file is never mistaken for
"claims nothing". The Docker image has everything this needs. Run straight from Python, the server also needs two
packages for it (`pip install h3 requests`); without them, the server says so once and does everything else as usual.

The full instructions are in the server's README in the source code.
