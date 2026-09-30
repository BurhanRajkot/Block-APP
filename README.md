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

Home screen → **+** → pick an app → enter a duration in hours → "Lock it". It disappears from
the active list on its own once the timer expires (handled by an exact alarm, re-armed on
reboot).

## Reels counter

The top of the Home screen shows how many short-form videos (Reels, Shorts, TikToks, …) you've
swiped through today, with the top few apps underneath. It reads scroll events from the same
Accessibility Service, only counts, never blocks anything, and stays on the phone like
everything else.

It works on Android 9+ and has no per-app list. Instead it recognises a full-screen vertical
pager, one that always comes to rest exactly one screen-height per swipe, and only starts
counting after two such swipes in a row. That keeps ordinary feed scrolling out of the count,
and the trade-off is that it undercounts rather than overcounts:

- A session of just one or two reels never gets confirmed, so it isn't counted.
- Rewatching (swiping back, then forward again) doesn't count a reel twice.
- A viewer whose pages don't fill the container (peeking previews), or a Compose app that
  doesn't report scroll distances, won't be counted at all.

Treat it as a rough daily signal, not an exact tally.

While you're in a reel viewer, the same total also floats in a tiny bubble at the top of the
screen, just right of the front camera, and hops each time it goes up. It disappears when you
leave for another app or scroll the app's normal feed. It can't be tapped, so it never gets in
the way of the app underneath.

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
