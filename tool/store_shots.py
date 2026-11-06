"""Store screenshots and Google Play's background-location video, made on the Android emulator.

A made-up household (The Example family: Alex, Sam, Jo) on Android Studio's emulator, with the demo-mode status bar
and a 1080 x 2160 screen (Play wants a phone screenshot's long side at most twice its short side). Build the full
debug APK first (./gradlew assembleFullDebug), start an emulator with a Google Play image, then run the steps in order:

    python tool/store_shots.py prep           # screen size, demo status bar, a fresh install
    python tool/store_shots.py household      # welcome and agreement shots, then the household on Alex's phone
    python tool/store_shots.py permissions    # every permission but location all the time
    python tool/store_shots.py video          # the disclosure, Android's screen, recording with the screen off
    python tool/store_shots.py places         # Home, School (Sam's, weekday times) and Work (Alex's)
    python tool/store_shots.py shots          # the main screen, places, a place's dialog, the QR code
    python tool/store_shots.py report         # the report's example at tablet width (needs the docs served on :8760)
    python tool/store_shots.py reset          # the emulator's screen and status bar back to normal

Everything lands in build/store-shots/. Copy the PNGs to fastlane/metadata/android/en-US/images/ (phoneScreenshots,
tenInchScreenshots), for F-Droid.

Google Play gets its own set, from the play build (no automatic test texts, Android's battery list instead of the
exemption dialog): build it (./gradlew assemblePlayDebug) and add --play to every step, after the step's name. That
set lands in build/store-shots/play/: its PNGs go to store/play/, and its video to YouTube, unlisted, for Play's
declarations. The steps tap the app's own
labels, so a renamed button needs the same change here.
"""
import html
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PLAY = "--play" in sys.argv[2:]
FLAVOR = "play" if PLAY else "full"
OUT = REPO / "build" / "store-shots" / ("play" if PLAY else "")
APK = REPO / "app" / "build" / "outputs" / "apk" / FLAVOR / "debug" / f"app-{FLAVOR}-debug.apk"
PKG = "com.storiedev.familycoverage"
SDK = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
           or Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "Sdk")
ADB = str(SDK / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))
HOME = (39.237255, -123.150032)  # the emulator's own default location: nobody's real place
NODE = re.compile(r"<node\b([^>]*)>")
ATTR = re.compile(r'([\w-]+)="([^"]*)"')


def adb(*args, binary=False):
    r = subprocess.run([ADB, *args], capture_output=True, check=False)
    return r.stdout if binary else r.stdout.decode("utf-8", "replace")


def nodes():
    """The screen's views from uiautomator, as dicts (read with a regex: the dump is one tag per view)."""
    for _ in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
        xml = adb("shell", "cat", "/sdcard/ui.xml")
        if "<hierarchy" in xml:
            break
        time.sleep(1)
    out = []
    for m in NODE.finditer(xml):
        a = {k: html.unescape(v) for k, v in ATTR.findall(m.group(1))}
        b = [int(x) for x in re.findall(r"\d+", a.get("bounds", ""))]
        if len(b) == 4:
            out.append({"text": a.get("text", ""), "desc": a.get("content-desc", ""),
                        "checked": a.get("checked") == "true", "x": (b[0] + b[2]) // 2, "y": (b[1] + b[3]) // 2})
    return out


def find(label):
    ns = nodes()
    for match in ((lambda s: s == label), (lambda s: label.lower() in s.lower())):
        hits = [n for n in ns if match(n["text"]) or match(n["desc"])]
        if hits:
            return hits[0]
    return None


def wait(label, secs=15):
    end = time.time() + secs
    while time.time() < end:
        n = find(label)
        if n is not None:
            return n
        time.sleep(0.7)
    sys.exit(f"timed out waiting for {label!r}")


def tap_at(n):
    adb("shell", "input", "tap", str(n["x"]), str(n["y"]))
    time.sleep(0.8)


def tap(label, secs=10):
    tap_at(wait(label, secs))


def type_text(text):
    esc = text.replace("%", "\\%").replace(" ", "%s")
    adb("shell", "input", "text", "".join("\\" + c if c in "()<>|;&*~\"'`$!?[]{}#" else c for c in esc))


def key(name):
    adb("shell", "input", "keyevent", "KEYCODE_" + name)


def shot(name):
    OUT.mkdir(parents=True, exist_ok=True)
    time.sleep(1.0)
    (OUT / f"{name}.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
    print("shot", name)


def scroll_to(label, down=True, tries=12):
    for _ in range(tries):
        n = find(label)
        if n is not None and 200 < n["y"] < 1900:
            return n
        a, b = ("1500", "800") if down else ("800", "1500")
        adb("shell", "input", "swipe", "540", a, "540", b, "250")
        time.sleep(0.6)
    sys.exit(f"couldn't scroll to {label!r}")


def top():
    for _ in range(10):
        adb("shell", "input", "swipe", "540", "500", "540", "1900", "120")
    time.sleep(0.6)


def geo(lat, lon):
    adb("emu", "geo", "fix", str(lon), str(lat))
    time.sleep(1.5)


def edit_place(name):
    row = next(x for x in nodes() if x["text"].startswith("● " + name))
    tap_at(next(x for x in nodes() if x["text"] == "Edit" and abs(x["y"] - row["y"]) < 80))
    wait("Whose test texts")


def members_for(*names):
    """Ticks exactly these members in a place's dialog, whatever it started with."""
    for member in ("Alex", "Sam", "Jo"):
        n = next(x for x in nodes() if x["text"] == member)
        if n["checked"] != (member in names):
            tap_at(n)


def demo_bar():
    adb("shell", "settings", "put", "global", "sysui_demo_allowed", "1")
    for extra in (
        ["-e", "command", "enter"],
        ["-e", "command", "clock", "-e", "hhmm", "0915"],
        ["-e", "command", "battery", "-e", "level", "86", "-e", "plugged", "false"],
        # The emulator's SystemUI ignores the mobile icon's demo values (it showed "3G"): hide it, full Wi-Fi.
        ["-e", "command", "network", "-e", "mobile", "hide"],
        ["-e", "command", "network", "-e", "wifi", "show", "-e", "level", "4", "-e", "fully", "true"],
        ["-e", "command", "notifications", "-e", "visible", "false"],
    ):
        adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", *extra)


def step_prep():
    adb("shell", "wm", "size", "1080x2160")
    demo_bar()
    adb("uninstall", PKG)
    print(adb("install", str(APK)).strip().splitlines()[-1])
    geo(*HOME)
    adb("shell", "am", "start", "-n", f"{PKG}/.MainActivity")
    wait("Start a household")


def step_household():
    shot("01-welcome")
    tap("Start a household")
    tap("The Smiths")
    type_text("The Example family")
    tap("Alex")
    for i, member in enumerate(("Alex", "Sam", "Jo")):
        if i:
            key("ENTER")
        type_text(member)
    key("BACK")
    tap("Create the household")
    tap("Alex")
    tap("This is me")
    wait("What this phone records")
    shot("02-agreement")
    tap("I've read what this records")


def step_permissions():
    for step, button in (("Location: precise", "While using the app"), ("Phone: read each SIM", "Allow"),
                         ("Notifications: tap", "Allow")):
        tap(step)
        tap(button)
    if PLAY:
        # The play build opens Android's battery list, where the owner picks the app: the same result, set directly.
        adb("shell", "dumpsys", "deviceidle", "whitelist", "+" + PKG)
    else:
        tap("Battery: unrestricted")
        tap("Allow")


def clear_notifications():
    """Clears the emulator's own notices from the shade, so the video shows only the app's."""
    adb("shell", "cmd", "statusbar", "expand-notifications")
    time.sleep(1.5)
    try:
        tap("Clear all", 5)
    except SystemExit:
        pass  # nothing to clear
    adb("shell", "cmd", "statusbar", "collapse")
    time.sleep(1)


def step_video():
    # Play asks for 30 seconds or less: the step, the disclosure, Android's screen, Start, the screen off and back on
    # with newer readings, then the notification with its Stop button (the foreground service's declaration). Each
    # lookup of the screen takes a second or two, so the ones that can are made before the recording starts.
    clear_notifications()
    top()
    step = wait("Location: allow all the time")
    remote = "/sdcard/fc-background-location.mp4"
    rec = subprocess.Popen([ADB, "shell", "screenrecord", "--time-limit", "60", "--bit-rate", "6000000", remote])
    time.sleep(1.5)
    tap_at(step)
    cont = wait("Continue")  # the disclosure stays up while this reads the screen, and after: long enough to read
    time.sleep(2)
    tap_at(cont)
    tap_at(wait("Allow all the time"))
    key("BACK")
    tap("Start recording")
    time.sleep(3)  # the status turns to Recording, with a first reading
    key("POWER")  # the screen goes off; recording goes on
    time.sleep(4)
    key("WAKEUP")
    adb("shell", "wm", "dismiss-keyguard")
    time.sleep(2)  # newer readings in the status
    adb("shell", "cmd", "statusbar", "expand-notifications")
    time.sleep(1)
    n = find("Family Coverage is recording")
    if n is not None:  # a swipe down on a notification opens it, with its Stop button
        adb("shell", "input", "swipe", str(n["x"]), str(n["y"]), str(n["x"]), str(n["y"] + 400), "300")
    time.sleep(2)
    adb("shell", "cmd", "statusbar", "collapse")
    adb("shell", "pkill", "-INT", "screenrecord")
    rec.wait(timeout=30)
    time.sleep(1)
    OUT.mkdir(parents=True, exist_ok=True)
    adb("pull", remote, str(OUT / "fc-background-location.mp4"))
    print("video", OUT / "fc-background-location.mp4")


def step_places():
    top()
    for name, lat, lon in (("Home", *HOME), ("School", HOME[0] + 0.011, HOME[1] + 0.004),
                           ("Work", HOME[0] - 0.006, HOME[1] + 0.031)):
        geo(lat, lon)
        time.sleep(4)
        scroll_to("Add the spot you're at")
        tap("Add the spot you're at")
        tap("Home, school, work")
        type_text(name)
        key("BACK")
        tap("Add")
    geo(*HOME)
    time.sleep(3)
    edit_place("School")  # Sam's, with weekday times
    members_for("Sam")
    tap("Times, such as")
    type_text("8:15, 15:20")
    key("BACK")
    tap("Save")
    edit_place("Work")  # Alex's
    members_for("Alex")
    tap("Save")


def step_shots():
    top()
    shot("03-recording")
    n = scroll_to("Places")
    # The play build's test-texts note names the F-Droid build: Play's shots stop above it.
    adb("shell", "input", "swipe", "540", str(n["y"]), "540", "600" if PLAY else "260", "600")
    time.sleep(1.2)
    shot("04-places")
    edit_place("School")
    shot("05-place-edit")
    tap("Cancel")
    scroll_to("Share this household")
    tap("Share this household")
    wait("Copy the code")
    shot("06-share")
    key("BACK")


def step_report():
    # Tablet width: the report's tables don't fit a phone. Headless Edge or Chrome, light mode, 1080 x 1920.
    browsers = [Path(os.environ.get("PROGRAMFILES(X86)", "")) / "Microsoft/Edge/Application/msedge.exe",
                Path(os.environ.get("PROGRAMFILES", "")) / "Google/Chrome/Application/chrome.exe"]
    exe = next((str(b) for b in browsers if b.is_file()), None) or shutil.which("chromium") or shutil.which("google-chrome")
    if not exe:
        sys.exit("no Edge or Chrome found")
    OUT.mkdir(parents=True, exist_ok=True)
    subprocess.run([exe, "--headless=new", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1.125",
                    "--window-size=960,1707", "--blink-settings=preferredColorScheme=1", "--virtual-time-budget=6000",
                    f"--screenshot={OUT / '07-report-tablet.png'}", "http://localhost:8760/report/?demo=1"], check=True)


def step_reset():
    adb("shell", "wm", "size", "reset")
    adb("shell", "am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command", "exit")
    adb("shell", "settings", "put", "global", "sysui_demo_allowed", "0")


STEPS = {"prep": step_prep, "household": step_household, "permissions": step_permissions, "video": step_video,
         "places": step_places, "shots": step_shots, "report": step_report, "reset": step_reset}

if __name__ == "__main__":
    if not 2 <= len(sys.argv) <= 3 or sys.argv[1] not in STEPS or sys.argv[2:] not in ([], ["--play"]):
        sys.exit(__doc__)
    STEPS[sys.argv[1]]()
