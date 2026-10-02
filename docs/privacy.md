# Family Coverage privacy policy

*Draft: takes effect with the first public release.*

Family Coverage is made by **StorieDev**, a registered trade name of Steven Storie, a sole proprietor in
Massachusetts. Questions about privacy: **privacy@storiedev.com**, or PO Box 118, Lenox, MA 01240.

## The short version

Family Coverage sends nothing to StorieDev. There's no account, no analytics, no advertising and no crash reporting.
What it records stays on your phone until you export it, or until it's copied to a server your household runs.

## What the app records, and why

To compare mobile networks where your household goes, each phone records, until the household's end date:

- for each SIM: the carrier and network, the technology (4G, 5G), the serving cell, signal strength and quality,
  service state, how calls would be carried, and which SIM carries data;
- precise location with each reading, and a GPS track while moving;
- mobile data use per SIM: totals only, never which apps or sites;
- the results of its own tests: data checks, speed tests, and (if your household has a server) server tests;
- call and text tests that you record by tapping a button;
- if you turn them on, test texts between two household phones: when each was sent, delivered and received;
- the phone's battery level, to show the app's own cost.

Each phone's owner reads what their phone will record, and agrees, before it records anything. Recording stops on its
own after the household's end date.

## Where it goes

- **Your phone.** Rows go into files in the app's own storage on the phone, which other apps can't read. They're
  excluded from cloud backups and phone-to-phone transfers.
- **Exports you make.** "Export the data" writes a zip to wherever you choose. What happens to it then is up to you.
  The report page reads exports in your web browser and uploads nothing.
- **Your household's server, if it has one.** The phones copy their files to the address your household set up.
  StorieDev doesn't run, see or have access to it. The app refuses to send data over plain http unless the server is
  on your home network or a private VPN.
- **Test services.** Data checks contact Cloudflare's 1.1.1.1, and speed tests Cloudflare's speed test
  (speed.cloudflare.com). Like any website, Cloudflare sees your phone's internet address and the test traffic; the
  tests carry no personal information. See [Cloudflare's privacy policy](https://www.cloudflare.com/privacypolicy/).
  You can turn either test off.

StorieDev never receives any of it.

## Test texts

Test texts carry only a short tag, such as `FC test 7F3A12 13:30:05`. Your partner's phone numbers stay on your phone:
they're never exported, uploaded or written to any file. The F-Droid and GitHub build can receive texts while test
texts are on. It acts only on its own tags from your partner's numbers and drops every other message at once,
without storing it. It can't read the texts already on your phone. The Google Play build has no access to texts at
all.

## Camera

The camera is used only while you scan a household's QR code. Frames are decoded in memory and never stored.

## Children

Family Coverage isn't directed at children. Each person whose phone records must agree on their own phone, and a
parent setting up a teenager's phone should talk it through with them first: the recordings are location history.

## Keeping and deleting

The recordings stay on the phone until you delete them ("Delete the recorded data" in the app) or uninstall the app.
Leaving a household doesn't delete them. Copies you exported, or that went to your household's server, are yours and
your household's to delete.

## Your rights

StorieDev holds no data about you from this app, so there's nothing for us to give you, correct or delete. For copies
on your household's server, ask whoever runs it. If you have questions, write to privacy@storiedev.com.

## Changes

If this policy changes, the new version will be posted here with its date, and in the app's About section.
