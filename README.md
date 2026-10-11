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

2. **Open in Android Studio** (this environment has no Android SDK/Gradle/emulator installed, so
   building and running has to happen there): File → Open → select this folder. Studio will
   prompt to set up the Gradle wrapper automatically on first sync — accept that.

3. **Install on your phone** and, from the app's Home screen, tap "Permissions setup" to:
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
Insights has the per-app breakdown and the last 7 days. It reads screen events from the same
Accessibility Service, only counts, never blocks anything, and stays on the phone like
everything else.

It works on Android 9+ and has no per-app list. It recognises a reel viewer by shape: a
full-screen vertical pager showing exactly one video at a time, which comes to rest exactly one
screen-height per swipe. When the app also labels that pager as a pager (most do), it's
recognised on the first video, before you swipe. Otherwise the first page-sized swipe confirms
it. Ordinary feed scrolling ends a session instead of counting.

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

## Screen time

Insights (and the Today tab) show time per app, an hour-by-hour chart, how many times each app was
opened, and phone unlocks, for today and the past week. This needs **Usage access** (Settings →
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
