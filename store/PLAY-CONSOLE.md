# Google Play Console: the answers

Drafts for each form in the Play Console, for the `play` build. Google rewords these forms now and then: read each
question on the day, and change an answer here when one no longer fits. The answers marked **(judgment call)** are
ones a maintainer should decide on purpose. Checked against Play's policy pages on 2026-11-05 (sources at the end).

## Store listing

- **App name:** Family Coverage (`fastlane/metadata/android/en-US/title.txt`, 15 of 30 characters).
- **Short description:** `fastlane/metadata/android/en-US/short_description.txt` (77 of 80 characters).
- **Full description:** [store/play/full_description.txt](play/full_description.txt) (about 2,770 of 4,000
  characters). It's `full_description.txt` with the test-texts line written for the Play build, which can't send
  texts itself. Both carry the background-location disclosure, which Play wants in the description as well as in
  the app: "collects location data … even when the app is closed or not in use".
- **Category:** Tools. **Tags:** network, signal.
- **Contact:** hello@storiedev.com. **Website:** https://sstangle73.github.io/family-coverage/ (the help site,
  which has no donation link). Never storiedev.com/family-coverage, which has one.
- **Graphics:**
  - **Icon:** `fastlane/metadata/android/en-US/images/icon.png` (512 x 512, 32-bit with alpha, 3 KB).
  - **Feature graphic:** `fastlane/metadata/android/en-US/images/featureGraphic.png` (1024 x 500, 24-bit). Its
    source is `store/feature-graphic.html`.
  - **Phone screenshots:** the six in [store/play/phoneScreenshots/](play/phoneScreenshots/), 1080 x 2160, 24-bit
    with no alpha (Play refuses alpha). They're from the **play** build (`tool/store_shots.py <step> --play`), so
    they show its test texts (a reminder that opens Messages), not the F-Droid build's automatic ones. The places
    shot stops above the in-app note that names the F-Droid build.
  - **Tablet screenshots:** none **(judgment call)**. The only tablet-width shot is the report page, which is a web
    page rather than the app; a listing's screenshots are meant to show the app.
- **Nothing about donations anywhere in the listing:** Play's payments policy has been enforced against those (in
  September 2026 against AnkiDroid's in-app donation link too).

## App content

- **Privacy policy:** https://storiedev.com/privacy
- **Ads:** no.
- **App access:** "All functionality is available without special access." Add instructions anyway, since location
  gates recording: *No account or server is needed. On the first screen tap Start a household, give it any name and
  members, pick yourself, agree, and allow each step under Setup (location, then location all the time, phone,
  notifications). Tap Start recording. One SIM is enough; a second SIM adds a second network. A household server
  is optional and can be left blank.*
- **Target audience:** 18 and over. Not designed to appeal to children.
- **Content rating (IARC):** category *Utility, Productivity, Communication or Other*. No violence, sex, language,
  drugs, gambling or purchases.
  - *Displays user's location to others* (Play: answer yes "only if the app transmits the user's current, precise
    location to other people"): **Yes (judgment call).** Without a server, nothing leaves the phone. With a
    household server, the live report shows readings, places and dead spots to whoever has the household's report
    password. "No" would be true only for households without a server.
  - *Can users interact or exchange content?* No: test texts go through the phone's own Messages app.
- **News app, government app, health, financial features:** no, none.
- **Advertising ID:** not used. Declare that the app doesn't use it.

## Data safety

Play counts any data an app sends off the phone as *collected* ("transmitting data from your app off a user's
device"), even to a server the user runs; its exemption for uploads to a user's own cloud account is about a
provider with its own terms, not a home server. Family Coverage sends data off the phone only to the household's own
server, if the household sets one up. So:

- **Does the app collect or share any of the required user data types?** Yes (optional collection: only with a
  household server).
- **Is all of the user data collected by the app encrypted in transit?** **No (judgment call).** The app allows
  plain http to private addresses (a home network, or inside a VPN such as Tailscale, which encrypts on its own).
  Play allows "yes" only if it holds for all collected data. The alternative is requiring https in the Play build,
  which would stop households from using a server on their home network without a certificate.
- **Can users request that their data be deleted?** Yes: *Delete the recorded data* in the app deletes the phone's
  copy, and the household's server keeps its copy under the household's control. There are no accounts, so Play's
  account-deletion requirement doesn't apply and there's no deletion page to give.
- **Shared with third parties?** No. A copy the user exports, or the household server they set, is the user's own
  action. The speed tests and data checks fetch from Cloudflare's speed-test endpoints and send it no user data.

Data types, each *collected, optional, not shared, for app functionality, not processed ephemerally*:

| Play's data type | What it is in Family Coverage |
|---|---|
| Location: precise location | Each reading's location, the GPS track while moving, and the household's places |
| Personal info: name | The member name the household gives each phone (a first name or nickname) |
| App activity: app interactions | The call and text taps ("Call rang") |
| App activity: other user-generated content **(judgment call)** | The household's name and its places' names |
| App info and performance: diagnostics | The heartbeat: battery level, charging, app version |
| App info and performance: other app performance data **(judgment call)** | Each SIM's network, signal and service, the data checks and speed tests: Play has no type for network measurements, and this is the nearest |
| Device or other IDs | The install's random id, which the server uses to approve the phone |

Not collected: phone numbers (the partner's number stays on the phone), messages (the Play build has no SMS
permission), contacts, photos (the camera only reads a QR code, nothing is saved), and web browsing.

## Permissions declarations

### Location in the background

- **The feature:** "Family Coverage records which mobile network works where a household goes. It logs each SIM's
  signal with the phone's location, every 10 seconds while moving and every 2 minutes while still, for the few weeks
  of a study the household sets. Phones spend that time in pockets and bags with the screen off, so recording needs
  location in the background. Without it, the comparison would cover only the moments the app is open, not the
  drive to school or the hours at work."
- **The prominent disclosure:** the agreement text on the main screen ("records in the background, even when the app
  is closed or not in use"). Then, right before Android's own screen, the *Location in the background* dialog: it
  says "Family Coverage collects your precise location with each SIM's signal, to show which network works where.
  It does this in the background, even when the app is closed or not in use", and where the data goes. Play's rules
  (the word "location", one of its background phrases, before the runtime prompt, in normal use) are met. The store
  description says it too.
- **Video:** `python tool/store_shots.py video --play` records it on the emulator in about 30 seconds (Play: "aim
  for a video duration of 30 seconds or less"): the *Location: allow all the time* step, the *Location in the
  background* dialog, Android's *Allow all the time* screen, *Start recording*, the screen off and back on with a
  newer reading, and the notification with its Stop button. Upload it to YouTube, unlisted (Play's preferred
  format; a Google Drive MP4 is also accepted).

### Foreground service of type location

The form asks, for each type: a description, the impact if the task is deferred or interrupted, a use case, and a
video.

- **Use case:** the list's nearest is *Background location updates*; write it in as "Recording network coverage
  with location, started by the user".
- **Description:** "While a household's study runs, recording is a foreground service the user starts with Start
  recording. It shows a notification with a Stop button, so the person always knows it's recording and can stop
  it. It's of type location because every reading includes the phone's location."
- **If deferred or interrupted:** "Readings stop: the comparison would miss the time and places it was interrupted,
  such as a drive with the screen off, and the household's report would show gaps that aren't coverage gaps."
- **Video:** the same video. It shows Start recording, the screen off, and the notification with its Stop button.

No other declarations: the camera (QR codes) and the phone state permission need none, and the Play build has no
SMS or battery-exemption permission.

## Before production

- **The developer account:** a personal account needs a verified identity, developer email and phone, and an
  Android device verified in the Play Console app before it can publish. StorieDev's account is personal and was
  opened after 13 November 2023.
- **Closed testing:** such an account needs a closed test with at least 12 testers opted in for 14 days in a row
  before it can apply for production. The test is per app, but a tester list is per account: the same list can run
  this app's test alongside the other apps'.
- **The upload:** the release workflow's `play-bundle` artifact (an AAB signed with the upload key), to the closed
  testing track first. Let Play generate the app signing key (the default): the GitHub release keeps StorieDev's
  own key, so a Play install and a GitHub install can't update each other, which is what we want with two builds
  that differ.
- **Target API:** new apps need targetSdk 36 from 31 August 2026. The app targets 36.

## Coming later

- **Android developer verification:** apps installed from outside Play need a verified developer from
  30 September 2026 in Brazil, Indonesia, Singapore and Thailand, and everywhere in 2027. Play apps are registered
  automatically. The GitHub release's key will need registering for the same package in the Play Console; how
  F-Droid's own key fits is open.
- **targetSdk 37 (Android 17)** brings a Local Network declaration (from 25 August 2027) for reaching a server on the
  home network, and a declaration for precise location.

## Sources

- Background location: https://support.google.com/googleplay/android-developer/answer/9799150
- Foreground service types: https://support.google.com/googleplay/android-developer/answer/13392821
- Testing for new personal accounts: https://support.google.com/googleplay/android-developer/answer/14151465
- Data safety: https://support.google.com/googleplay/android-developer/answer/10787469
- Target API: https://support.google.com/googleplay/android-developer/answer/11926878
- Listing assets: https://support.google.com/googleplay/android-developer/answer/9866151
- Content rating: https://support.google.com/googleplay/android-developer/answer/6161128
- Developer verification: https://developer.android.com/developer-verification/guides
