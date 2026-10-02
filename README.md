# Family Coverage

**Which mobile network works where your family actually goes?** Family Coverage is an Android app for a household
that's thinking of switching carriers, or adding a second line. For a few weeks, each phone in the household records
its signal, its data and simple tests, for every SIM it has. Then you compare the networks place by place: home,
school, work, the grandparents', the drive in between. The data stays on your phones.

[![Buy me a coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-stevenstorie-FFDD00?logo=buymeacoffee&logoColor=black)](https://buymeacoffee.com/stevenstorie)

> **Status:** early (0.1). Not released yet: no store listing and no signed builds.

## How a household uses it

1. **One phone starts a household:** its name, its members, an end date, and optionally a server.
2. **The other phones join** by scanning that phone's QR code, or pasting its setup code.
3. **Everyone agrees** on their own phone to what it records, then starts recording.
4. **Add your places** by standing at each one and tapping *Add the spot you're at*.
5. **At the end, each phone exports a zip.** Open them all in the [report page](docs/report/), which reads them in
   your browser and uploads nothing.

A phone with two SIMs (a physical SIM and an eSIM, say) measures both networks at once, side by side. That's
the cheapest way to try a second carrier: a prepaid eSIM for a month.

## What it records

For **each SIM**, every 10 seconds while moving and about every 2 minutes while still:
- the network, the technology (LTE, 5G NSA, 5G SA) and the status-bar icon;
- band, channel, cell and signal (RSRP, RSRQ, SINR, plus the 5G leg);
- service state, voice transport (VoLTE, Wi-Fi calling), which SIM carries data, and whether Wi-Fi is up.

Also:
- a GPS track while moving;
- each SIM's mobile data use (totals, not which apps);
- off Wi-Fi, a tiny check that data works, and a small speed test over cellular every 30 to 60 minutes;
- a test that reaches your household's server, if you run one;
- call and text tests: buttons you tap when someone calls or texts you to test;
- optional **test texts** between two household phones at your places, from each SIM, both ways.

Nothing measures during a call. A household always has an end date, after which the app stops on its own. The
columns are in [docs/data-format.md](docs/data-format.md).

## Two builds

| | F-Droid and GitHub ("full") | Google Play ("play") |
|---|---|---|
| Test texts | Automatic: sent, answered and logged by the app, silently where both networks allow | One tap: the app opens Messages with the test filled in; the other person taps "arrived" |
| SMS permissions | Send and receive (never read) | None |
| Battery | Asks for the exemption directly | You set "Unrestricted" in Android's list |
| Donation link | In About | None |

Google Play lets an app use SMS permissions only if it's the default texting app or fits one of nine listed uses,
and network testing isn't one. The Play build therefore leaves SMS out entirely.

## Privacy

- **Local first.** Rows go into daily CSV files on the phone. They leave only in an export you make, or, if your
  household set one up, to your own server.
- **No accounts, no analytics, no ads.** Nothing goes to StorieDev.
- **Test texts** carry only a tag like `FC test 7F3A12 13:30:05`. Phone numbers stay on each phone; rows name the
  other person by their household name.
- The app never reads your calls, texts, contacts, Wi-Fi names or browsing.

The privacy policy is at [storiedev.com/privacy](https://storiedev.com/privacy); this repository's text of it is
[docs/privacy.md](docs/privacy.md).

## Optional server

Phones can copy their data to a small server your household runs (Python, standard library only, with a Docker
image). It serves the report page live, behind a password, so you can watch results come in as the phones upload,
and it answers the app's "reach my server" test. See [server/](server/).

## Building

```bash
./gradlew testFullDebugUnitTest testPlayDebugUnitTest assembleFullDebug assemblePlayDebug
```

You'll need the Android SDK (`local.properties` with `sdk.dir`) and JDK 17 or newer. The app uses platform APIs
only: no AndroidX, no Google services. Its one library is ZXing, for QR codes. Minimum Android 12.

## Contributing

Issues and pull requests are welcome. Please keep the two promises the app makes: data stays local unless the
household sends it somewhere, and nothing records without an end date.

## License

[Apache License 2.0](LICENSE). Made by **StorieDev**. If it helped your family decide, you can
[buy me a coffee](https://buymeacoffee.com/stevenstorie). ☕
