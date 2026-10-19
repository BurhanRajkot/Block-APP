package com.blockapp.android.usage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.blockapp.android.BlockApplication
import com.blockapp.android.util.ReelPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The daily alarm from AlarmScheduler.scheduleDailyArchive: archives recent screen time and
 * applies the reel plan's next step, for a phone where the app process itself rarely starts.
 */
class UsageArchiveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as BlockApplication
        val pendingResult = goAsync()
        app.applicationScope.launch {
            try {
                UsageArchive.archiveRecent(context, app.repository)
                ReelPlan.refresh(context, app.repository)
            } catch (e: CancellationException) {
                throw e
            } catch (e: RuntimeException) {
                // A statistic must not crash the process that enforces locks (invariant 9).
                Log.w("UsageArchive", "daily archive failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
