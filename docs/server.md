# Running your own server

Optional. Without a server, each phone keeps its data until you export it, and that's enough to decide.

A server suits a household that would rather not export from every phone, or that wants to know whether a home
server (cameras, files, a media server) is usable from where the family goes: the app tests reaching it hourly.

It's a single Python file, standard library only, with a Docker image. In short:

1. Run it at home (`docker compose up -d --build` in the `server` folder of the source code).
2. Make it reachable: on your home network or a private VPN such as Tailscale over plain http, or from anywhere
   over https behind a reverse proxy with a certificate.
3. In the app, **Edit the household** on one phone and set the server's address. Share the household again, and
   scan the updated code on the other phones. Each person agrees again, because the data now goes somewhere new.
4. Approve each phone on the server (`approve <device_id>`). The id is in each phone's status.
5. At the end, `export-zips` writes the same zips the phones export, for the report page.

The full instructions are in the server's README in the source code.
