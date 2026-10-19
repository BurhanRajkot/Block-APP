package com.blockapp.android

import android.app.Application
import android.util.Log
import com.blockapp.android.alarm.AlarmScheduler
import com.blockapp.android.data.AppDatabase
import com.blockapp.android.data.BlockRepository
import com.blockapp.android.service.BlockGuardService
import com.blockapp.android.usage.UsageArchive
import com.blockapp.android.util.ReelPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class BlockApplication : Application() {

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var repository: BlockRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = BlockRepository(
            context = applicationContext,
            database = AppDatabase.getInstance(this),
            scope = applicationScope,
        )

        // Sweep expired locks on every process start, not just after a device reboot (see
        // BootCompletedReceiver). The expiry AlarmManager callback can be missed — the exact
        // alarm permission can be revoked, the process can be force-stopped and never see the
        // broadcast, the clock can jump — and without this, a lock whose blockUntil already
        // passed stays "active" in Room forever: the accessibility service keeps enforcing it
        // and the UI shows it stuck at "Unlocking…" indefinitely. Doing this before the
        // reactor below starts watching activeLocks also means a lock found stale at cold
        // start settles before that reactor sees it, rather than after.
        applicationScope.launch {
            repository.expireAllDue()
            // Re-arm every running lock's expiry alarm. A force-stop cancels all of an app's
            // alarms, and so does an uninstall: a lock restored by Auto Backup on reinstall
            // comes back with none. Enforcement never needed them (see the sweep above), but
            // without this such a lock only cleared on the next process start or Home resume.
            repository.getActiveOnce().forEach { lock ->
                AlarmScheduler.schedule(this@BlockApplication, lock.packageName, lock.blockUntil)
            }
            AlarmScheduler.scheduleDailyArchive(this@BlockApplication)
        }

        // History archive and today's reel-plan step. Neither is needed for the first frame, and
        // the archive replays a week of usage events, so it waits until the launch has settled
        // rather than competing with it for the CPU on a cold start.
        applicationScope.launch {
            delay(DEFERRED_STARTUP_WORK_MS)
            // applicationScope has no exception handler, so anything thrown here would kill
            // the process, and the accessibility service and every running lock with it, over
            // a statistic (CLAUDE.md invariant 9).
            try {
                UsageArchive.archiveRecent(this@BlockApplication, repository)
                ReelPlan.refresh(this@BlockApplication, repository)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                Log.w(TAG, "deferred startup work failed", e)
            }
        }

        // Keep the process (and the bound accessibility service) at foreground priority for
        // as long as any lock is active — see BlockGuardService's doc for why this is needed.
        //
        // Debounced: activeLocks can still emit a rapid burst of transient values (Room's Flow
        // re-querying, the expireAllDue() sweep above landing right after the first snapshot,
        // etc.), and reacting to every single one can start and then stop the foreground
        // service faster than Android's own start-up handshake for it completes — which crashes
        // the whole process with a ForegroundServiceDidNotStartInTimeException (observed while
        // testing: a stale expired lock at cold start flipped activeLocks from non-empty to
        // empty within milliseconds of app launch and took the process down). Waiting for the
        // signal to settle avoids that without weakening the guarantee: the service still ends
        // up in the right state for as long as a lock is genuinely active.
        repository.activeLocks
            .debounce(SERVICE_TOGGLE_DEBOUNCE_MS)
            .onEach { locks ->
                if (locks.isNotEmpty()) BlockGuardService.start(this) else BlockGuardService.stop(this)
            }
            .launchIn(applicationScope)
    }

    private companion object {
        const val TAG = "BlockApplication"
        const val SERVICE_TOGGLE_DEBOUNCE_MS = 300L

        /** Long enough to be past a cold start's first frames, short enough to beat a quick exit. */
        const val DEFERRED_STARTUP_WORK_MS = 4_000L
    }
}
