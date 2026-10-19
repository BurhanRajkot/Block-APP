# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android app that lets a user lock a chosen app for a set duration, with no in-app way to
unlock or uninstall it early. The only early-exit path is an offline-signed "unlock key" minted
by the developer on their own PC via the `keygen/` scripts. It runs as a normal app on a regular
phone (no Device Owner / factory-reset provisioning), so protection is enforced through an
Accessibility Service + Device Admin, not the kernel.

This is a single-user personal tool, not a shipped product: there is no test suite, no CI, no
crash reporting, and no analytics. Verification is manual, on a real device.

## Project layout

- `app/` — the Android app (Kotlin, Jetpack Compose, Room, minSdk 26, compile/targetSdk 34).
- `keygen/` — standalone Python scripts run on the developer's own machine to mint unlock keys.
  Never ships inside the app. Requires `pip install cryptography`.
  `keygen/keys/private_key.pem` is the signing key, is gitignored, and must never be committed,
  pasted into a file under `app/`, or printed into any log.

Package layout under `app/src/main/java/com/blockapp/android/`: `accessibility/` (enforcement),
`admin/` (Device Admin), `alarm/` (expiry + boot), `data/` (Room + repository), `keys/` (unlock-key
verification), `service/` (keep-alive foreground service), `ui/` (Compose screens), `usage/`
(screen-time stats), `util/` (package helpers).

## Commands

The SDK is installed (`local.properties` → `~/Android/Sdk`, platform android-34) and the Gradle
wrapper works from the command line, so a change can and should be compile-checked here before
being called done. Running on a device still happens via Android Studio or `installRelease`.

```bash
./gradlew assembleDebug        # build debug APK
./gradlew installRelease       # build + install the fast build on a connected device
./gradlew installDebug         # debug build, for the debugger only
./gradlew lintDebug            # Android Lint → app/build/reports/lint-results-debug.sarif
./gradlew clean                # clear build outputs
```

A compile is *not* a verification of behaviour on this app — see "Verifying a change" below.

**Put the release build on the phone, not the debug one.** A debuggable build runs Compose with no
ahead-of-time compilation and no R8, and that was most of why the app felt laggy on a Pixel. The
release type is minified, resource-shrunk (about 1.4MB against 21MB for debug), signed with the
local debug key so it needs no keystore, and ships `src/main/baseline-prof.txt`, which
`profileinstaller` applies on a sideload. Debug and release share a signature, so either installs
over the other without losing data. Log calls are kept in release on purpose: the reel counter
is debugged with `adb logcat -s ReelTracker` on the build that's actually installed.

**KSP is pinned to the KSP1 backend** (`ksp.useKSP2=false` in `gradle.properties`, KSP
`2.2.10-2.0.2` in the root `build.gradle.kts`). Room 2.6.1's processor crashes under KSP2 with
`unexpected jvm signature V` and fails `:app:kspDebugKotlin` before emitting anything. The two
settings only move together, and only with a Room upgrade — KSP 2.3+ deletes the KSP1 backend
outright, so bumping KSP alone hard-fails at configuration time. The `Language version 1.9 is
deprecated` warning on every build comes from this backend and is expected.

There are no unit or instrumentation tests, so there is no test command and nothing to run a
single test against. Do not claim a change is "tested" — say what was built and what still needs
manual on-device verification.

Generating an unlock key (from a dev machine, once `keygen/keys/private_key.pem` exists):

```bash
python3 keygen/generate_key.py com.instagram.android now   # unlock a package immediately
python3 keygen/generate_key.py com.instagram.android 2     # push its unlock time out 2h instead
python3 keygen/generate_key.py "*" now                     # unlock everything immediately
```

Rotating the signing keypair (invalidates every key ever issued):

```bash
rm keygen/keys/private_key.pem
python3 keygen/generate_keypair.py   # paste the printed public key into PublicKeyProvider.kt
```

## Architecture

**Dependency wiring is manual.** There is no DI framework. `BlockApplication` constructs the
single `BlockRepository` and exposes it plus an `applicationScope`. Everything reaches it the same
way — `context.applicationContext as BlockApplication` — from composables, services, and
receivers alike. Keep that pattern; don't introduce a container.

**Data flow.** `BlockRepository` (`data/`) is the single read/write entry point for lock state,
shared by the UI, the accessibility service, and the alarm/boot receivers. It wraps Room
(`AppDatabase`, `BlockDao`, `BlockedAppEntity`, `UsedNonceEntity`) and mirrors active locks into an
in-memory `StateFlow<Map<packageName, blockUntil>>` (`activeLocks`) so the accessibility service can
check the foreground app synchronously, without a DB hit, on every window event. Locks are
soft-deleted (`active = 0`), never `DELETE`d.

Two deliberate reads bypass the cache, and both have a reason recorded at the call site:
`getActiveLockUntil()` (accessibility service just reconnected after a process death — the Flow
may not have populated yet) and `getActiveOnce()` (receivers running before/outside the cache).

**Enforcement is two-tiered**, both in `AppBlockAccessibilityService` (`accessibility/`):

- *Tier 2 — locks.* A locked package reaching the foreground is kicked home via
  `performGlobalAction(GLOBAL_ACTION_HOME)` and `BlockOverlayActivity` is shown
  `OVERLAY_DELAY_MS` later. HOME runs asynchronously and used to land on top of a wall started
  at the same moment, so the wall was never seen. Don't fold the start back into the same
  instant. Two paths feed it: the live `onAccessibilityEvent` (`typeWindowStateChanged`, `notificationTimeout=0`) and a
  200ms watchdog poll (`WATCHDOG_INTERVAL_MS`) that runs *only* while `activeLocks` is non-empty.
  The watchdog exists because window-state events get coalesced or dropped under rapid
  open/close taps, and a single missed event otherwise means the app stays open indefinitely —
  the "blocking stops working after a while" bug.
- *Tier 1 — self-protection.* Once Device Admin is active, the service also bounces the user out
  of Settings' Accessibility screens, the Device Admin list/detail screens, this app's own App
  Info / uninstall screens, and the Play Store uninstall flow — matched by package
  (`ProtectedPackages`) plus **substring** class-name hints in `isProtectiveSystemScreen`. For
  screens shared by all apps (App Info, uninstaller), `currentWindowMentionsSelf()` further
  requires that *this* app is the target.

Tier 1 is gated on Device Admin already being active, and that gate is load-bearing: Accessibility
must be enabled *before* Device Admin can be activated, so an unconditional Tier 1 would bounce the
user out of the Device Admin activation screen and make setup impossible to complete.

**Reel counter (observe-only).** The same service also receives `typeViewScrolled` and
`typeWindowContentChanged` and hands them to `ReelTracker`, which counts short-form videos (and
the time spent on them) per app per day into `reel_counts` for `HomeScreen` and
`HistoryScreen`. **It only watches the apps in `util/ReelApps.KNOWN`**: the tracker and swipe
detector drop every other package before doing anything. It used to watch every app on screen
shape alone, and browsing images in Chrome counted as reels, because a full-screen viewer that
snaps one item at a time *is* the reel shape. Don't widen it back to a blocklist. In
`ReelApps.NEEDS_VIDEO_EVIDENCE` (Reddit, LinkedIn, X, Pinterest, Tumblr: photos and videos share
viewers) a scanner match also needs a video player on screen (`ReelScreenScanner.VideoCheck`:
ExoPlayer `exo_` IDs, player/video view classes, a seek bar, mute/pause/play labels, never
caption text). A swipe alone never opens a session: it needs a recent scanner candidate, or
pager semantics or a single visible item plus a second snapped swipe, and in evidence apps only a
candidate. Within the tracked apps it uses three independent signals, because no one of them
works in every app:

- `ReelScreenScanner` walks the active window (throttled to about once every 800ms, on a
  background `HandlerThread`) looking for a full-screen scrollable showing exactly one
  edge-to-edge child. That shape plus a second signal (page up/down actions, or a reel-ish view
  ID such as `reel_recycler` / `clips_viewer_view_pager`) opens a session on the *first* reel,
  before any swipe. It also counts a page change as a reel unless a measured swipe claims it
  within `PAGE_CHANGE_GRACE_MS`, and that is the *only* counter that works for Compose apps:
  Compose reports scroll deltas in an estimated unit, not pixels (a 2209px page swipe arrived
  as −7px on an emulator), and drops semantics-free page containers from the tree, so a Compose
  pager is recognised by its page up/down actions alone. Returning to a reel is recognised by
  the page's visible text, because Compose rebuilds the node when a page comes back.
- The **action rail**, `ReelScreenScanner.RailScan`, is the fallback when no pager is
  recognisable, which on a real phone was YouTube Shorts and everything else except Instagram.
  It looks for a column of small buttons down the right edge, at least two of them labelled
  with a reaction word or a count ("Like", "12K"), over a full-screen backdrop. Feeds put those
  actions in a row, never a right-hand column. The column must name **at least two different
  reactions** and sit in one compact cluster: a comments list has a "Like" heart down the right
  of every comment, and a regular post's comments screen used to count as reels, one more per
  scroll. A rail has no page node, so a new reel is told apart by the words in the caption zone
  (bottom-left, above the nav bar), minus chrome words ("Follow", "Original audio"), compared
  by overlap with the *smaller* set, **and** by the rail's own counts changing. Caption text
  alone changed under auto-captions and an expanded "…more", and each change counted a reel.
  A scanner-seen page change only counts if that page is still on screen when the grace ends.
- `ReelScrollDetector` measures swipes. A burst that lands exactly on whole container heights is
  a pager. One that doesn't is a feed. Inside a session it takes two such swipes in a row to end
  it: ending on one hid the pill mid-reel and the 30s feed cooldown stopped counting. That
  confirms viewers the scanner wasn't sure about, and counts by furthest position so rewatching
  doesn't double-count. Another app drawing while a session is open triggers a scan that ends
  the session if that app is really in front, so a dropped window event can't leave the pill
  over the launcher.

Two service-config flags exist only for this, and removing either silently breaks counting:
`flagIncludeNotImportantViews`, because the system drops scroll events from views marked
not important unless asked, and many pagers are; and `flagReportViewIds` for the hints.
`SurfaceView`/`TextureView` report as plain `android.view.View`, so "is a video playing" can't
be detected by class name. Don't try.

All reel geometry is measured against the **real display size** (`ReelTracker.realScreenSize`),
not `resources.displayMetrics`, which leaves out the system bars while node bounds include them.
Against the smaller height the caption zone missed the caption, and every reel read as the
same one.

The tracker never acts on the foreground app. The one action reel tracking leads to is **Tier 3**
in `AppBlockAccessibilityService.enforceReelLimit`: an open reel session gets
`GLOBAL_ACTION_BACK` plus the reel wall (`BlockOverlayActivity` with `EXTRA_REEL_LIMIT`) when
today's total has reached the **daily reel limit** (`util/ReelLimit`), or at any count during
the plan's **reel-free hours** (`EXTRA_QUIET_FROM` set). It only fires on sessions, which never
exist for this app, launchers or `ProtectedPackages`. Lowering the limit applies now; raising or
removing it is pending until tomorrow, the same "only ever tighten" rule as invariant 4. A limit
of **0 is real** ("no reels", where the quit plan ends). Only `null`/`OFF` means no limit, so
never test a limit with `> 0`, and `EXTRA_REEL_LIMIT`'s "absent" value is `Int.MIN_VALUE`, not 0.
Content-change events must never reach Tier 1/2, only the tracker's throttle. An exception from it
must not reach the service (see `BlockRepository.addReelsWatched` and the guards in `ReelTracker`).
Requires API 28 (`scrollDeltaY`). Every decision is logged under `adb logcat -s ReelTracker` for
on-device debugging.

**The reel plan** (`util/ReelPlan`) is what the onboarding quiz (`QuizScreen`, shown once on
first launch, `ReelPlan.quizSeen`) builds: a weekly step-down from just under the user's own
estimate to a goal, plus optional reel-free hours. `ReelPlan.build` is pure. `ReelPlan.refresh`
writes the day's step into `ReelLimit`, and only ever lowers it. It follows the user down early
once three complete days average well under the schedule (`limitFor`). It runs on process
start (deferred 4s), on Home resume, from the daily `UsageArchiveReceiver` alarm, and at
midnight from the service. Stopping the plan and narrowing reel-free hours, including by
retaking the quiz, take effect *tomorrow*. Otherwise "retake the quiz with gentler answers"
would be an in-app off switch. The plan lives in SharedPreferences (`reel_plan`), like
`ReelLimit`.

`ReelBubble` is its on-screen half: a tiny `TYPE_ACCESSIBILITY_OVERLAY` pill beside the camera
cutout showing today's total. It is shown for exactly as long as the tracker has a session open.
The session ends on a feed-like scroll, when the viewer leaves the screen, when another app
comes to the front, and on screen-off (a receiver in the service), so the pill never sits over
the lock screen. It needs no `SYSTEM_ALERT_WINDOW`, but only through the service's own
`WindowManager`, and it must stay `FLAG_NOT_TOUCHABLE` because it sits over other apps' top
bars. Its WindowManager calls are guarded for the same reason as the tracker.

**History survives the system's week and a reinstall.** The system keeps raw usage events for
about a week, so `usage/UsageArchive` copies each day into Room (`usage_days`, `usage_apps`,
schema v4). It runs on process start, from the daily inexact `UsageArchiveReceiver` alarm
(`AlarmScheduler.scheduleDailyArchive`, re-armed on boot and on every start), whenever History
opens, and on each of Home's minute refreshes. `BlockRepository.archiveUsage` never writes an
empty day, and never replaces a day with a smaller one: an old day replays as empty or short,
and either would erase what the archive had. `HistoryScreen` reads *only* Room, back to the
earlier of the install date and the first archived day. **Auto Backup is on**
(`res/xml/backup_rules.xml` / `data_extraction_rules.xml`: database and shared prefs), so a
reinstall brings back history, the plan, running locks and used nonces. Locks and nonces coming
back is deliberate. Uninstalling is not a way out of a lock, and a spent key stays spent. A
restored lock has no alarm, which is why `BlockApplication` re-arms every running lock's alarm on
start. That also covers a force-stop, which cancels them. The backup goes through the system's
backup service. The app still has no network permission (invariant 8).

**Screen time** (`usage/UsageStatsProvider.history`) replays the raw `queryEvents` resume/pause
stream rather than reading `queryUsageStats` buckets. The buckets overlap the requested range,
which inflated "today", and can't be split by hour. It needs Usage access, and every screen
that shows it degrades to a grant prompt without it.

**The only sanctioned way out** is `RemoveProtectionScreen`, which calls
`DeviceAdminHelper.removeAdmin()` in-process — an app may always drop its own admin without
touching Settings UI, so this path never needs the screens Tier 1 blocks.

**`ProtectedPackages`** (`util/`) is the single source of truth for packages that must never be
lockable: Settings variants across OEMs, package installers, Play Store. It is enforced twice on
purpose — `InstalledAppsProvider` excludes them from the picker, and `BlockRepository.lockApp`
refuses them as a backstop — because locking any of them would make Tier 2 bounce the very screens
setup and removal depend on.

**Process keep-alive.** `BlockApplication` starts/stops `BlockGuardService` (a `specialUse`
foreground service) as `activeLocks` becomes non-empty/empty, **debounced 300ms**
(`SERVICE_TOGGLE_DEBOUNCE_MS`). Its only job is holding the process at foreground priority so the
bound accessibility service isn't killed by an OEM battery manager mid-lock. Only
`BlockApplication` may start or stop it — calling `BlockGuardService.start/stop` from UI code
fights that observer.

**Expiry is defence-in-depth, and Room's `blockUntil` is the only source of truth.** Alarms are a
convenience that can be missed (exact-alarm permission revoked, process force-stopped, clock jump),
so expiry is swept from three places: `AlarmScheduler`'s exact alarm (falling back to an inexact
alarm rather than throwing), `BootCompletedReceiver` (re-arms alarms after reboot), and
`expireAllDue()` on every process start plus every `HomeScreen` resume. Enforcement never depends
on an alarm firing.

**Unlock keys** (`keys/KeyVerifier.kt` ⟷ `keygen/generate_key.py`). Format:
`base64url(payload) + "." + base64url(signature)`, where payload is
`"packageName|newUnlockEpochMillis|nonce"` signed `SHA256withRSA` / PKCS#1 v1.5. The app holds only
the public key (`PublicKeyProvider.kt`). Nonces are single-use, recorded in `used_nonces`, so a key
cannot be replayed. `newUntil <= now` means "unlock now"; a future value *extends* the lock instead.
Target `"*"` applies to all active locks. The two sides are one wire format across two languages:
change one and you must change the other in lockstep — including the Base64 padding detail called
out in `generate_key.py`.

## Invariants that must not break

Ordered by how bad it is to get wrong. The first one can force a factory reset.

1. **The escape hatch must always work.** While Tier 1 is armed, `RemoveProtectionScreen` →
   `DeviceAdminHelper.removeAdmin()` is the user's *only* way to regain access to Accessibility
   and Device Admin settings. If a change crashes that screen, hides it, or leaves it unreachable
   from `HomeScreen`, a locked-out user's remaining options are an unlock key from the dev machine
   or Safe Mode. Any change touching `RemoveProtectionScreen`, `MainActivity`'s navigation, or
   `removeAdmin()` must be verified on-device *with Device Admin actually active*.
2. **Never make Tier 1 unconditional** or otherwise arm it before Device Admin is active — it
   makes first-time setup impossible to complete (see above).
3. **Never lock a package in `ProtectedPackages`**, and keep both enforcement points intact.
4. **A lock may only ever be extended, never shortened — except by an unlock key.** `BlockDao.insert`
   is `OnConflictStrategy.REPLACE`, so `BlockRepository.lockApp` merges against any existing active
   lock (`maxOf` on `blockUntil`, original `blockedAt` preserved) instead of overwriting it.
   Without that merge, re-locking an already-locked app for a shorter time from the app picker
   ends the running lock early — a fully in-app early exit, which is the one thing this app
   exists to prevent. `applyUnlockKey` is the only path allowed to bring a `blockUntil` forward,
   because those keys can only be minted off-device.
5. **Keep Tier 1's class-name hints narrow.** They are `contains(ignoreCase = true)` substring
   matches against Settings' activity names. A hint like `"Settings"` or `"App"` would bounce the
   user out of most of the Settings app, taking the escape hatch with it. New hints should be
   specific activity names, and ones matching screens shared by other apps must be paired with
   `currentWindowMentionsSelf()`.
6. **Don't remove the 300ms foreground-service debounce.** Reacting to every `activeLocks` emission
   can start and stop the service faster than Android's start-up handshake completes, which kills
   the whole process with `ForegroundServiceDidNotStartInTimeException`. This was hit in practice
   at cold start when a stale expired lock flipped the flow non-empty→empty within milliseconds.
7. **A Room schema change needs a version bump and a migration.** `AppDatabase` is `version = 4`
   with `exportSchema = false` and **no** `fallbackToDestructiveMigration()`, so shipping a changed
   schema without a migration throws at open — and on this app, a DB that won't open means
   `activeLocks` never populates and enforcement silently stops. Migrations are hand-written SQL;
   copy the `CREATE TABLE` from `createAllTables` in the generated `AppDatabase_Impl` rather than
   writing it by hand, since Room validates the result column for column.
8. **Don't add the `INTERNET` permission.** The manifest declares no network permission at all;
   key verification is entirely offline and local. Zero network reach is a deliberate property of
   the design, not an omission.
9. **Never weaken a permission fallback into a crash.** An exception on a background path
   (scheduling, verification, package lookup) can abort mid-lock and leave a lock active with no
   expiry ever scheduled. Degrade instead — that is why `AlarmScheduler` falls back to an inexact
   alarm and `KeyVerifier` returns `Invalid` for any malformed input.

## Code conventions

Follow what's already there; the existing files are consistent. Concretely:

**Comments explain the failure mode, not the mechanic.** This is the strongest convention in the
repo and the most important one to continue. Comments here don't say *what* the code does — they
name the specific bug the code prevents, and often that it was observed in testing. See
`BlockApplication`'s debounce, the `watchdog` KDoc, `AlarmScheduler.schedule`, and
`UsageStatsProvider`'s `INTERVAL_BEST` note. When you write a line whose *absence* would cause a
subtle bug, record which bug, so the next person doesn't "simplify" it away. Skip comments on code
that is already obvious.

**Class-level KDoc states the contract and the constraints**, including who is allowed to call it
(`BlockGuardService`: "Only BlockApplication starts/stops this") and what must not change. Put
cross-references to other files in prose — this codebase leans on them heavily.

**Stateless helpers are `object` singletons that take `Context` as a parameter** rather than
holding one: `DeviceAdminHelper`, `AlarmScheduler`, `KeyVerifier`, `ProtectedPackages`,
`InstalledAppsProvider`, `UsageStatsProvider`. Don't add a class with an injected `Context` for
this kind of helper.

**UI look.** Dark-only on purpose (`ui/theme/Color.kt`): neutral graphite surfaces (`Ink*`),
text in `Bone*`, Lime (`primary`, with `LimeInk` on it) for anything the user does, and Ember
orange (`tertiary`) for the reel counter only. **No gradients and no glows**: the indigo and
violet-to-coral look before this one read as generated, so emphasis comes from size and weight.
Screens are assembled from `ui/UiKit.kt` (`Panel`, `ScreenHeader`,
`PrimaryButton`, `BarChart`, `ProgressRing`, `SegmentedToggle`, `ChoiceCard`, `HeatCalendar`,
`LoadingRows`, …), so reach for those before styling a Material component by hand. Charts are
drawn with `Canvas`; no chart library. Illustrations are static `Canvas` drawings in `ui/Art.kt`,
not image files. The launcher icon is a vector adaptive icon with its own cut-out monochrome layer.

**Never touch PackageManager during composition.** App lists load through `rememberLaunchableApps`
and icons through `rememberAppIdentity` (`ui/AppIdentity.kt`), both off the main thread and
cached. `InstalledAppsProvider.label` memoises labels, because resolving one loads that app's
resources. Calling `listLaunchableApps` inside `remember {}` froze the picker for most of a
second. And nothing at the top level of a screen may change every second: Home's countdowns tick
inside each `LockCard`, because a ticker read at the top recomposed the whole screen once a second.
`RemoveProtectionScreen` and `UnlockKeyScreen` deliberately only pick up the theme, so their
structure (invariant 1) isn't touched by visual work. `OnboardingScreen` was rebuilt on the kit
(one required step open at a time) but keeps "Remove protection" in its header in every state.

**The restricted-settings walk-through** (`admin/RestrictedSettings`, Android 13+). On a
sideloaded install Android greys out the Accessibility switch, and App info's ⋮ → "Allow
restricted settings" item **only appears after the switch has been tried once** and the
"Restricted setting" dialog shown. Onboarding walks it in that order: try the switch, App info,
switch again. Whether the app is restricted can't be read (system-only API), so
`likelyRestricted` guesses from `InstallSourceInfo.packageSource` and only picks wording.

**Motion** lives in `ui/Motion.kt` (`Reveal`, `AnimatedCount`, `RollingText`, `PulsingDot`) and
`LockedCelebration.kt`. One hard rule: **anything that animates forever must be drawn in a
`Canvas`, never animated through `size()`, offsets or `graphicsLayer`.** Compose reports every
change to a node's on-screen bounds, transforms included, to accessibility services. A looping
`graphicsLayer` scale on the status dot fired ~8 `TYPE_WINDOW_CONTENT_CHANGED` events a second
from an idle Home screen, waking every accessibility service on the phone, this app's included.
Check with `adb shell uiautomator events` on an idle screen: it should print nothing. Finite
animations (arrival, press, count-up) are fine.

**UI is plain composables, no ViewModels.** Each screen is a top-level `@Composable` taking
`onDone`/`onBack`-style lambdas; state is `remember { mutableStateOf(...) }`; navigation is a
`private sealed class Screen` + `when` block in `MainActivity`. Repository data is collected with
`LaunchedEffect`, and anything that must re-read system state after the user returns from Settings
uses `LifecycleResumeEffect` (a one-shot `LaunchedEffect(Unit)` won't fire — the composable stays
alive while the user is away).

**Tunable numbers become named constants in a `private companion object`** at the bottom of the
file, with a comment on what they trade off — `WATCHDOG_INTERVAL_MS`, `SERVICE_TOGGLE_DEBOUNCE_MS`.
No bare magic numbers on behavioural paths.

**Room:** `suspend` for one-shot reads/writes, `Flow` for observation, soft-delete via
`active = 0`. Query strings live in `BlockDao` annotations; no query building elsewhere.

**Broadcast receivers that do async work use `goAsync()`** with `pendingResult.finish()` inside the
coroutine (`BootCompletedReceiver`), never a fire-and-forget launch.

**API-level differences are guarded explicitly** with `Build.VERSION.SDK_INT` and a comment saying
which API changed and why it matters, with `@Suppress("DEPRECATION")` scoped to the legacy branch
only (`UsageStatsProvider.hasUsageAccess`).

**Strings:** `res/values/strings.xml` holds only text the *system* renders — app label,
accessibility-service description, notification channel/title/text. In-app Compose text is
inline English literals. Match that split; don't half-migrate the UI to string resources.

**Formatting:** 4-space indent, ~100-column limit including comments, trailing commas on
multi-line argument lists, explicit imports (no wildcards).

## Dependencies

Deliberately minimal: Compose BOM + Material3, `core-ktx`, lifecycle (incl.
`lifecycle-runtime-compose` for `LifecycleResumeEffect`), `activity-compose`, Room + KSP, and
`profileinstaller` (applies the baseline profile on a sideload). Nothing
else, and no version catalog — versions are literals in `app/build.gradle.kts`, with plugin
versions in the root `build.gradle.kts`.

Don't add without a real need: any DI framework (Hilt/Koin) — `BlockApplication` is the wiring;
Navigation-Compose — the `sealed class` + `when` (three bottom-bar tabs, Today/History/Settings,
plus seven sub-screens) is
sufficient; WorkManager —
`AlarmManager` already covers expiry and exact timing matters; any networking, analytics, or crash
library — see invariant 8; LiveData or RxJava — the codebase is coroutines/Flow throughout.

Kotlin, the Compose compiler plugin, and KSP versions move together — bumping one alone will fail
the build.

## Before changing anything

1. **Locate the mechanism first.** Enforcement is spread across the accessibility service,
   `BlockApplication`, `AlarmScheduler`, and `BlockRepository` by design. Grep for the symbol
   across all of them before editing one; a change to `activeLocks` semantics touches the UI, the
   watchdog, and the foreground service at once.
2. **Read the comments before deciding something is redundant.** Most of the defensive code here
   is load-bearing and says so. A debounce, a duplicate sweep, or a second enforcement point is
   usually a recorded bug fix, not an oversight.
3. **Ask what happens when the process dies mid-lock**, since that is this app's normal failure
   mode, not an edge case. New state must survive process death (Room), and anything recovered at
   startup must be recovered in `BlockApplication`/`onServiceConnected`, not only in the UI.
4. **Check both directions of a permission being absent.** Every protective capability here is
   revocable at any moment. New code depending on one needs a defined behaviour when it's missing.
5. **Keep the user's way out intact** — re-read invariant 1 for anything touching navigation,
   removal, or admin state.

## Verifying a change

No automated tests, so on-device verification is the only real signal. A real device also matters
because OEM battery managers, Settings activity class names, and Safe Mode behave differently
than an emulator. Worth walking through for enforcement changes:

- Lock an app, confirm it bounces to the overlay, and confirm the countdown ticks.
- Force-stop the app from Settings, reopen the locked app — enforcement should resume
  (`onServiceConnected` recovery + watchdog).
- Reboot with a lock active — the lock should survive and still auto-expire on time.
- Let a short lock expire while the app is backgrounded — it should clear itself, not stick at
  "Unlocking…".
- With Device Admin active, try to reach Settings → Accessibility, the Device Admin list, and this
  app's App Info — each should bounce, and the rest of Settings should stay usable.
- Confirm `RemoveProtectionScreen` still deactivates admin and returns control.
- Apply an unlock key, then apply the same key again — the second attempt must be rejected as
  already used.
- Upgrade over a v3 install (not a fresh one): the DB must open, locks and reel counts intact.
- With reel-free hours covering now, open a reel viewer: BACK plus the "Reel-free hours" wall.
  With the limit at 0 ("No reels"), the first reel should do the same.
- Retake the quiz with gentler answers: today's limit and reel-free hours must not loosen.
- Uninstall and reinstall after a backup (`adb shell bmgr backupnow com.blockapp.android`): History,
  the plan and running locks should all come back.

## Threat model

This is a best-effort deterrent, not a cryptographic guarantee, and the README is deliberately
explicit about that. A determined user can still disable the Accessibility service from Settings,
deactivate Device Admin and uninstall, or boot into Safe Mode (which disables all third-party
accessibility services). Closing those would require Device Owner provisioning on a factory-reset
device, which is out of scope for a daily-driver phone.

Keep that framing in code comments, UI copy, and commit messages — don't describe any of the
bounce-back behaviour as making escape impossible. The honest claim is that it makes impulsive
unlocking meaningfully harder.

Note for any future thought of distribution: this app relies on the Accessibility API for
non-accessibility purposes and declares `QUERY_ALL_PACKAGES`, both of which are restricted under
Play Store policy. It is built as a personal sideloaded tool.
