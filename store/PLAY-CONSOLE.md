# Google Play Console: the answers

Drafts for each form in the Play Console, for the `play` build. Google rewords these forms now and then: read each
question on the day, and change an answer here when one no longer fits. The answers marked **(judgment call)** are
ones a maintainer should decide on purpose.

## Store listing

- **App name:** Family Coverage (`fastlane/metadata/android/en-US/title.txt`).
- **Short description:** `fastlane/metadata/android/en-US/short_description.txt` (78 of 80 characters).
- **Full description:** `full_description.txt`, with one line changed for the Play build, which can't send texts
  itself. Replace the test-texts line with:
  `<li>Optional test texts at your places: the app reminds you and fills in Messages, and the other phone notes when it arrives</li>`
- **Category:** Tools. **Tags:** network, signal.
- **Contact:** hello@storiedev.com. **Website:** https://sstangle73.github.io/family-coverage/
- **Graphics,** all in `fastlane/metadata/android/en-US/images/`: the 512 px `icon.png`, the 1024 x 500
  `featureGraphic.png`, six phone screenshots (`phoneScreenshots/`, 1080 x 2160: Play wants a phone shot's long side
  at most twice its short side) and the report at tablet width (`tenInchScreenshots/`). `tool/store_shots.py` makes
  the screenshots again after the screens change.
- **Nothing about donations anywhere in the listing:** Play's payments policy has been enforced against those.

## App content

- **Privacy policy:** https://storiedev.com/privacy
- **Ads:** no.
- **App access:** all functionality is available without special access. There are no accounts; a reviewer starts a
  household with any name on the first screen.
- **Target audience:** 18 and over. Not designed to appeal to children.
- **Content rating (IARC):** category *Utility, Productivity, Communication or Other*. No violence, sex, language,
  drugs, gambling or purchases.
  - *Does the app share the user's location with other users?* **Yes (judgment call).** Without a server, nothing
    leaves the phone. With a household server, the live report shows readings, places and dead spots to whoever
    has the household's report password. "Yes" adds the *Shares location* label; "No" would be true only for
    households without a server.
  - *Can users interact or exchange content?* No: test texts go through the phone's own Messages app.
- **News app, government app, COVID-19 app, health, financial features:** no, none.
- **Advertising ID:** not used. Declare that the app doesn't use it.

## Data safety

Play counts any data an app sends off the phone as *collected*, even to a server the user runs. Family Coverage sends
data off the phone only to the household's own server, if the household sets one up. So:

- **Does the app collect or share any of the required user data types?** Yes (optional collection: only with a
  household server).
- **Is all of the user data collected by the app encrypted in transit?** **No (judgment call).** The app allows
  plain http to private addresses (a home network, or inside a VPN such as Tailscale, which encrypts on its own).
  Answering "yes" would be true only for https servers. The alternative is requiring https in the Play build, which
  would stop households from using a server on their home network without a certificate.
- **Can users request that their data be deleted?** Yes: *Delete the recorded data* in the app deletes the phone's
  copy, and the household's server keeps its copy under the household's control. There are no accounts, so there's
  no account-deletion page to give.
- **Shared with third parties?** No. A copy the user exports, or the household server they set, is the user's own
  action.

Data types, each *collected, optional, not shared, for app functionality, not processed ephemerally*:

| Play's data type | What it is in Family Coverage |
|---|---|
| Location: precise location | Each reading's location, and the GPS track while moving |
| Personal info: name | The member name the household gives each phone (a first name or nickname) |
| App activity: app interactions | The call and text taps ("Call rang") |
| App info and performance: diagnostics | The heartbeat: battery level, charging, app version |
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
  is closed or not in use"). Then, right before Android's own screen, the *Location in the background* dialog says
  what's collected, why, and where it goes.
- **Video (unlisted on YouTube):** `tool/store_shots.py video` records it on the emulator, 75 s: the agreement, the
  *Location in the background* dialog, Android's *Allow all the time* screen, *Start recording*, 20 s with the screen
  off, and newer readings in the status after it wakes.

### Foreground service of type location

- **The task:** "While a household's study runs, recording is a foreground service with a notification and a Stop
  button, so the person always knows it's recording and can stop it. It's of type location because every reading
  includes the phone's location."
- **Video:** the same video, showing the notification and its Stop button.

No other declarations: the camera (QR codes) and the phone state permission need none, and the Play build has no
SMS or battery-exemption permission.

## Before production

- **Closed testing:** a personal developer account opened after 13 November 2023 needs a closed test with at least
  12 testers opted in for 14 days in a row before it can apply for production. An organization account doesn't.
- **The upload:** the release workflow's `play-bundle` artifact (an AAB signed with the upload key), to the testing
  track first.
