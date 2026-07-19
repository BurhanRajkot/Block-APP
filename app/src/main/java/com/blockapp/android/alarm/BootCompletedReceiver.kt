package com.blockapp.android.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blockapp.android.BlockApplication
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as BlockApplication
        val pendingResult = goAsync()
        app.applicationScope.launch {
            app.repository.expireAllDue()
            app.repository.getActiveOnce().forEach { entity ->
                if (entity.blockUntil > System.currentTimeMillis()) {
                    AlarmScheduler.schedule(context, entity.packageName, entity.blockUntil)
                }
            }
            pendingResult.finish()
        }
    }
}
