# Family Coverage

**Which mobile network works where your family actually goes?**

Coverage maps show where a carrier *says* it works. Family Coverage shows where it *does* work for you: at home,
at school, at work, at the grandparents', and on the roads in between. For a few weeks, each phone in your household
records its signal and runs small tests, on every SIM it has. Then you compare the networks place by place.

- **Your phones, your data.** Everything stays on the phones until you export it. No account, no ads, no analytics.
- **Two networks at once.** Put a second carrier's prepaid eSIM in a phone for a month, and it measures both side by
  side, at the same moments and places.
- **A real comparison.** The [report page](report/) reads every phone's export in your browser and shows time with
  service, signal, 5G, data checks, speed tests and test texts, for each network at each of your places. It answers
  the question for each person: how each network would have done wherever they actually went. It follows your regular
  drives and trips, with each network's gaps along the way. A map puts
  what the phones measured beside what each carrier claims to the FCC. For a phone with two lines, it shows what the
  second line covered when the first had none, and whether data moved over. And it shows how fast each phone's
  battery went down. [See an example with made-up data](report/?demo=1).

## Getting it

Family Coverage runs on Android 12 and newer. On each phone, download the APK from the
[latest release on GitHub](https://github.com/sstangle73/family-coverage/releases/latest) and open it; Android asks
once to allow installs from your browser. Then [set up the household](setup).

Every release is signed with the same StorieDev key, so a new version installs over the old one and keeps your
recordings. To hear about new versions, watch the repository's releases on GitHub. A Google Play version is on its
way. Google signs that one, so a phone stays with the source it started from: Play can't update an install from
GitHub, or the other way round.

## How it works

1. **[Set up the household](setup):** one phone starts it, the others join by QR code.
2. **Everyone agrees** on their own phone to what it records, then starts recording.
3. **Add your places** by standing at each one.
4. **Carry on as normal** for two to six weeks. The app stops by itself on the end date.
5. **Export from each phone** and open the zips in the [report page](report/).

## More

- [Setting up a household](setup)
- [What each column means](data-format)
- [Privacy](privacy)
- [Running your own server](server) (optional)

Family Coverage is free, open-source software under the Apache License 2.0, made by StorieDev.
