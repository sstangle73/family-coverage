# Testing on a phone

The checks to run on a real phone before a release, and on any new kind of phone. Use one that isn't recording for
anything else. It needs Android 12 or newer and at least one SIM with mobile data; two SIMs show the side-by-side
comparison. A second phone is optional, for the QR code join.

Note the phone's make, model, Android version and SIMs, then work down the list, writing down anything that crashes,
looks wrong or confuses you.

**The emulator first.** Android Studio's emulator (a Google Play image, API 31 or newer) runs most of this list:
setup, places, sharing by pasted code, taps, export and the report. Its one SIM is in service. Simulate the partner's
texts with `adb emu sms send <their number> "FC test 0A1B2C 12:00:00"`, and airplane mode with
`adb shell cmd connectivity airplane-mode enable`. It can't stand in for real SIMs, dual SIM, Wi-Fi calling, scanning
a QR code with the camera, battery use or days in a pocket, so a real phone still has the last word.

## Install

1. Install the APK (from a release, or `app-full-debug.apk` for a test build). Google Play Protect warns about the full
   build ("Harmful app blocked") because it can send and receive texts. Choose **More details > Install anyway**. The
   Play build has no SMS permission, so no warning.
2. Open it: the welcome screen offers **Start a household** and **Join a household**.

## Set up

3. **Start a household:** name `Test household`, members `Tester` and `Second` on two lines, the default end date, no
   server. Create it, then choose **Tester**.
4. Read **What this phone records**: it should name the end date and say the data stays on the phone. Tick the
   agreement; the text folds away and the box says *Agreed on <date>*.
5. Work down **Setup**: each button turns to ✓ when allowed. Location "all the time" opens Android's settings page;
   Battery opens the exemption prompt (full build) or Android's battery list (Play build). On Samsung, also add the app
   to *Never sleeping apps*.
6. **Start recording.** Within about 15 seconds the status shows ● Recording, a line per SIM (network, LTE or 5G,
   dBm, service), the location, and *Power: moving...*. A notification with a **Stop** button appears.
7. Leave the phone still for 5 minutes: *Power* changes to *still: sleeping, sampling about every 2 min*.

## Places, sharing and joining

8. **Add the spot you're at:** name it `Home`, 150 m. It appears under Places with its distance.
9. **Edit** Home: untick **Second** under *Whose test texts it's for*, keep Mon to Fri, and type `25:00` as the time.
   **Save** keeps the dialog open with a reason. Change the time to `8:15, 15:20` and save: the row adds *for Tester*
   and *tests Mon-Fri 08:15, 15:20*.
10. **Share this household:** a QR code shows. **Share the code as text** opens the share sheet; **Copy the code**
    copies it.
11. **Join**, on a second phone if you have one: install, **Join a household > Scan the QR code**, allow the camera, and
    point it at the first phone. The preview lists the name, members, end date and 1 place. **Join**, choose
    **Second**. You don't need to start recording there. Its Places list shows Home *for Tester* too.
12. Without a second phone, on this one: **Scan an updated code > Paste a setup code**, paste, **Use this code**. The
    preview says *Update this phone*; take it. You stay **Tester**, and Home keeps its times.

## Tests and texts

13. **Call and text tests:** pick a SIM, tap **✓ Call rang**: a line says it was recorded. **Undo the last tap** takes
    it back.
14. **Test texts** (full build): tick **Send and answer test texts**, choose **Second**, and enter the number of
    another phone you have as *Your partner's main number*. Allow SMS when the new Setup step asks. Tap **Send a test
    now**. The other phone receives `FC test XXXXXX hh:mm:ss`, visibly (leave the silent boxes unticked). Nothing
    answers it unless that phone runs the app too.
15. **Measurements:** untick and re-tick both; the agreement text changes to match.

## Leave it running

16. Carry the phone for a day: a drive, a place without Wi-Fi.
    - The SIM lines keep updating, and *Last data check* and *Last speed test* show results off Wi-Fi.
    - Note the battery drop per hour, still and moving.
17. **Wi-Fi calling alone**, if a SIM has Wi-Fi calling: turn on airplane mode, then turn Wi-Fi back on, and wait two
    minutes. That SIM's line shows `NONE`, then `POWER_OFF` or `OUT_OF_SERVICE`, and `voice IWLAN`: never
    `IN_SERVICE`, because Wi-Fi calling isn't coverage. Turn airplane mode off.
18. **Restart the phone.** Recording comes back by itself within a minute or two of unlocking.

## With a server (optional)

19. **The server check.** In **Edit the household**, enter `https://example.com` and save: *✗ Something answered at
    that address, but it isn't a Family Coverage server*, and nothing is saved. Then `http://192.168.1.250` (nothing
    there): *▲ No answer from that address from here* and *Save anyway* / *Change it*; choose **Change it**.
20. Run the server (`server/README.md`), **Edit the household** to set its address (*✓ Found it*), agree again, and
    approve the phone on the server. Within 15 minutes on Wi-Fi, the server's `list` shows the phone approved with a
    recent *last* time, and the app's **Open the live report** opens its `/report/`, which shows the readings once you
    enter the report password.
21. If the server is a Tailscale node and the phone reaches it through Tailscale: off Wi-Fi, *Last server test* ends
    with *Tailscale direct -> direct* or *derp -> direct* (the first test runs about 7 minutes after recording starts).

## Export, report, clean up

22. **Export the data**, save the zip (Downloads or Drive) and copy it to a computer.
23. Open `docs/report/index.html` from the source code in a browser (it works straight from the file) and choose the
    zip. The household, your SIMs' networks and the Home place appear, with no errors. **Battery** shows a rate per
    hour for the phone (parked, at least), and with two SIMs, **Two lines on one phone** lists any line that lost
    service off Wi-Fi for two minutes or more (or says none did).
24. **Stop recording**, **Delete the recorded data**, then **Leave the household**: the welcome screen returns.

## What to send back

The phone and Android version, which steps failed or surprised you, a screenshot of anything odd, and the battery
numbers from step 16.
