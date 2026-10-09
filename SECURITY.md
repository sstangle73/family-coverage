# Security

## Reporting a vulnerability

Please report a security problem privately, not in a public issue: use
**[Report a vulnerability](https://github.com/sstangle73/family-coverage/security/advisories/new)** on this
repository's *Security* tab. Only you and the maintainer see the report.

Say what you can of:
- what's affected: the app (which build, its version and the Android version), the server, or the report page;
- how to reproduce it, or a proof of concept;
- what someone could do with it.

## What happens next

- You'll hear back within 7 days.
- Once it's confirmed, it gets fixed, and a GitHub security advisory is published that credits you, unless you'd
  rather not be named. A CVE is requested through GitHub when the problem warrants one.
- Please keep it private until a fix is out, or for 90 days after your report, whichever comes first.

This is a one-person project with no bug bounty.

## Supported versions

Only the latest version gets fixes. Family Coverage is early (0.1).

## What's in scope

The Android app (both builds), the optional server in [`server/`](server/) and the report page in
[`docs/report/`](docs/report/). The promises that matter most:
- **The data stays on the phones** unless the household sends it somewhere: anything that sends readings, places or
  names elsewhere, or lets another app read them, breaks it.
- **A household is closed:** nobody should join one, or read its data, without its setup code.
- **Nothing records** without each person's consent, or after the household's end date.
- **The server** serves a household's data only behind its password, and **the report page** uploads nothing.

Out of scope: how a household hosts its own server (TLS, a reverse proxy), attacks that need an unlocked phone, root
or ADB, and denial of service by sheer volume.
