# App Blocker

Pick an app, lock it for a set duration, and there's no in-app way to unlock or uninstall it
early. The only early-exit path is an offline signed "unlock key" you (the developer) generate
on your own machine.

## What's actually enforced, and what isn't

This runs as a normal app on a regular, already-set-up phone (no factory reset / Device Owner
provisioning). That means real limits apply:

- **Locked apps**: detected via an Accessibility Service and bounced to the home screen the
  instant they come to the foreground. Effective as long as the service stays enabled.
- **Uninstalling the blocker itself**: registering as a Device Admin means Android requires
  deactivating admin *before* uninstall is even offered — a real, deliberate extra step, with a
  custom warning shown in the system's confirmation dialog.
- **Reaching the screens that would undo the above**: while a lock is active, the Accessibility
  Service also bounces out of Settings' Accessibility screen (the one place it could be silently
  disabled with no confirmation dialog), the Device Admin list/detail screen (first step toward
  deactivating admin), and this app's own App Info / uninstall screens. Only guarded while a lock
  is actually running — otherwise all of these are freely reachable, same as any app.
- **What this does NOT do**: guarantee it's impossible to escape. All of the bounce-back tricks
  above are best-effort, not a guarantee — a determined user can still get to Settings →
  Accessibility and disable the service, deactivate Device Admin and uninstall, or boot into Safe
  Mode (which disables all 3rd-party accessibility services). There's no way around this without
  Device Owner mode, which requires provisioning a factory-reset device via ADB — not applicable
  here since this runs on a daily-driver phone.

In short: strong deterrent against impulsive unlocking, not a cryptographic guarantee against a
determined bypass.

## Project layout

- `app/` — the Android app (Kotlin, Jetpack Compose, Room, minSdk 26).
- `keygen/` — standalone Python scripts you run on your own PC to mint unlock keys. Never ships
  inside the app.

## One-time setup

1. **Generate the signing keypair** (already done once in this repo — `keygen/keys/private_key.pem`
   exists and its public key is already embedded in
   `app/src/main/java/com/blockapp/android/keys/PublicKeyProvider.kt`). If you ever need to
   rotate it: delete `keygen/keys/private_key.pem`, run
   `python3 keygen/generate_keypair.py`, and paste the printed public key into
   `PublicKeyProvider.kt`.
   - Requires the `cryptography` package: `pip install cryptography` (already installed here).
   - **Keep `keygen/keys/private_key.pem` secret.** It's the only thing that can produce a valid
     unlock key. It's already excluded via `.gitignore`.

2. **Build and install the release build** with the phone connected over USB:
   `./gradlew installRelease`. Use this rather than the debug build: the debug build is
   unoptimised and noticeably laggy. The release build is signed with the local debug key, so it
   needs no keystore and installs over a debug install without losing data.

3. **First launch** asks five questions about your scrolling and builds a reel plan (or skip it).
   Then setup walks you through the permissions:
   - Enable the Accessibility Service.
   - Activate Device Admin.
   - Allow the app to ignore battery optimizations (so it doesn't get killed in the background).

## Locking an app

Today tab → **Lock an app** → pick an app → pick a duration → confirm. (Or tap the lock icon
next to any app in your screen-time list.) It disappears from
the active list on its own once the timer expires (handled by an exact alarm, re-armed on
reboot).

## Reels counter

The Today tab shows how many short-form videos (Reels, Shorts, TikToks, Reddit/LinkedIn video
feeds, …) you've swiped through today, roughly how long you spent on them, and the top few apps.
History has the per-app breakdown for any day, week or month. It reads screen events from the same
Accessibility Service, only counts, never blocks anything, and stays on the phone like
everything else.

It works on Android 9+ and only watches short-video apps (Instagram, YouTube, TikTok, Snapchat,
Facebook, Reddit, LinkedIn, X, Pinterest and a few more, listed in `util/ReelApps.kt`). Browsers,
galleries and everything else are never counted, however much their scrolling looks like reels. In
apps that also show photos full-screen (Reddit, LinkedIn, X, Pinterest, Tumblr) a reel only counts
while a video player is on screen. It recognises a reel viewer two ways. The
first is the app's video pager (a full-screen page per video, as in Instagram Reels). The
second is what every short-video viewer draws: a column of like / comment / share buttons down
the right edge, which feeds never have. That second way is what covers YouTube Shorts, TikTok,
Facebook and the rest. Either way it's picked up on the first video, before you swipe, and a
new reel is spotted when the caption and username change.

- Rewatching (swiping back, then forward again) doesn't count a reel twice.
- Coming back to the same reel (from comments, the notification shade, another app) doesn't
  count it again.
- Time is the gap between reels, capped at 3 minutes per reel, so a phone left on one video
  doesn't log an hour.
- A viewer whose pages don't fill the screen (peeking previews) won't be counted.

Treat it as a rough daily signal, not an exact tally. To see why something did or didn't count,
run `adb logcat -s ReelTracker` while scrolling.

While you're in a reel viewer, the same total also floats in a tiny bubble at the top of the
screen, just right of the front camera, and hops each time it goes up. It disappears when you
leave the viewer, switch apps, or turn the screen off. It can't be tapped, so it never gets in
the way of the app underneath.

## Reel plan

The first-launch quiz asks which apps get you, how many reels and how much time a day, when it's
hardest to stop, and what you want (cut down gently, cut down fast, or quit reels). From that it
builds a plan: a daily limit that starts a little under your own estimate and steps down each
week to a goal, plus optional **reel-free hours** (for example 11 PM – 7 AM) during which any reel
viewer is closed straight away. If you watch noticeably fewer reels than planned for three days,
the limit follows you down early. The plan never loosens on the day: stopping it, or rebuilding
it with gentler answers, only takes effect tomorrow. Today tab → the plan strip, or Settings →
Reel plan.

## Daily reel limit

Today tab → **Set a limit** on the reels card (or Settings → Daily reel limit). Pick None (no reels at
all), 25, 50, 100, 150, 200 or a custom number. A running reel plan sets this for you each week. The bubble then reads "87/100" and turns red near the end,
and at 10 to go you get a heads-up. When you hit it, any reel viewer you open is closed with
Back and a "Reel limit reached" screen stays up until midnight. The rest of each app keeps
working.

Lowering the limit applies immediately. Raising it or turning it off only applies from the
next day, so it can't be lifted on impulse.

## Screen time and history

The History tab shows time per app, an hour-by-hour chart, how many times each app was opened,
phone unlocks and reels, for any day since install. Switch between Day, Week and Month, and tap any
day on the month calendar or any bar in the week to open it. Android itself only keeps about a
week of usage data, so the app saves each day as it goes. Android's own backup (to your Google
account) includes that history and the reel plan, so both come back after an uninstall and
reinstall. Running locks come back too. This needs **Usage access** (Settings →
Special app access), which only reads how long apps were in front. Tap the lock icon on any app
row to lock that app straight away.

## Generating an unlock key

From your PC, once `keygen/keys/private_key.pem` exists:

```bash
# Unlock a specific package immediately
python3 keygen/generate_key.py com.instagram.android now

# Push a specific package's unlock time out by 2 more hours instead of lifting it
python3 keygen/generate_key.py com.instagram.android 2

# Unlock everything immediately
python3 keygen/generate_key.py "*" now
```

Paste the printed key into the app's "Enter unlock key" screen. Each key can only be applied
once (replay-protected via a nonce stored in the local database).
