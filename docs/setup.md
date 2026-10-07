# Setting up a household

You'll need Android 12 or newer on each phone that records. Set aside ten minutes per phone.

## 1. Plan it

- **Who records?** Everyone whose phone goes to places that matter: the people who drive the kids, commute, or
  work somewhere with poor signal. A household can have up to 12 members.
- **Which networks?** Each phone measures the SIMs it has. To try a carrier you're not on, add its **prepaid eSIM**
  as a second line for a month. The phone then measures both networks at the same moments and places: the fairest
  comparison there is. Turn off its data if you only want it measured, or leave automatic data switching on to see
  how the phone uses it.
- **How long?** Two weeks shows the routine; six weeks catches the odd trips. The longest a household can run is a
  year, and every household has an end date. After it the app stops on its own.

## 2. Start the household (first phone)

Install Family Coverage and choose **Start a household**:
- a **name** ("The Smiths");
- the **members**, one per line, everyone who'll record (you too);
- the **end date**;
- a **server**, only if you run one (most households don't: [see the server page](server)).

When you enter a server, the app checks it before saving: ✓ when it's a Family Coverage server that takes your
household, ✗ when something else answers at that address (another kind of server, say), and ▲ when there's no answer
from where you are, which is expected away from a server that's only on your home network or VPN. A phone joining
with the setup code shows the same check.

Then choose which member you are.

## 3. Add the other phones

On the first phone, open **Share this household (add a phone)**. On each other phone, install the app, choose
**Join a household** and scan the QR code. Far apart? Use **Share the code as text** and paste it on the other phone.

The code carries the household's name, members, end date, server and places. No phone numbers, and none of the
recorded data.

## 4. Agree, allow, start

Each person reads **What this phone records** on their own phone and ticks the agreement. That text names your
household's real end date and server. Then work down the **Setup** list:

| Permission | Why |
|---|---|
| Location, precise and all the time | Each reading's place, and recording with the screen off |
| Phone | Each SIM's network, which SIM carries data, and whether a call is on (to pause during calls) |
| Notifications | The "recording" notification, with its Stop button, and a note if recording stops |
| Battery: unrestricted | So Android doesn't put the recording to sleep |
| SMS (F-Droid and GitHub build, only with test texts on) | Sending and receiving the test texts |

**Samsung phones** also need Family Coverage in *Settings > Battery > Background usage limits > Never sleeping apps*.

Then tap **Start recording**.

If recording ever stops by itself, say because a permission was turned off, a notification says *Family Coverage
stopped recording* and why: tap it, allow what's marked ✗ and start again. Or, to stop for good, tap **Turn recording
off** on the notification or in the app, and nothing asks again. At the end date it stops for good, and a notification
says so.

## 5. Add your places

Stand at each place that matters and tap **Add the spot you're at**. Give it a name and choose how far around it
counts (150 m suits a house; 250 to 500 m a school campus or an office park). Share the household again afterwards,
and on the other phones choose **Scan an updated code**, so every phone knows every place.

Two choices for test texts (step 6), when adding a place or later with **Edit**:
- **Whose test texts it's for.** A place for some members only, such as one parent's office, starts tests only on
  their phones. The report still compares networks there with everyone's readings.
- **Fallback times**, such as `8:15, 15:20` on weekdays for the school run: at each time, a test goes out wherever
  the phone is, unless one went out in the hour before.

## 6. Test texts (optional)

Test texts check that texts get through, on every line, both ways. Choose two phones, usually the two that are apart
most of the day. On each, under **Test texts**, choose who to test with and enter their number(s). Numbers stay on
the phone.

- **F-Droid and GitHub build:** fully automatic. After 10 minutes at one of your places the phone texts its partner
  from each SIM, and the partner's phone answers. At most 8 exchanges a day, never 9 pm to 7 am, never during a call.
  Between lines that carry "silent" texts (data SMS), no Messages app shows them; otherwise they're visible texts
  like `FC test 7F3A12 13:30:05`. In the US, Verizon-network lines carry silent texts; AT&T's network (Cricket too)
  doesn't. Not sure? Leave the silent boxes unticked.
- **Google Play build:** the app reminds you at a place to send a test, and opens Messages with it filled in. When
  your partner's test reaches you, tap **Text arrived**.

## 7. During the weeks

Nothing to do. Recording pauses during calls and sleeps between readings when the phone sits still. It uses about
1 to 2 GB of mobile data a month for its tests. Turn speed tests or data checks off under **Measurements** if that's
too much.

If someone calls or texts you to test coverage, open the app and tap what happened (**Call rang**, **Text didn't
arrive**...). That's the best evidence of all.

## 8. Decide

On each phone, **Export the data** and save the zip somewhere you can reach from a computer (Drive, email to
yourself, a USB cable). Open the **[report page](report/)** and choose all the zips at once. Look at your places
first: that's where the decision is.

When you're done, **Delete the recorded data** on each phone, or uninstall the app.
