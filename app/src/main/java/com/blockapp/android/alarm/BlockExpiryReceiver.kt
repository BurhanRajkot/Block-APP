package com.blockapp.android.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blockapp.android.BlockApplication
import kotlinx.coroutines.launch

class BlockExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val packageName = intent.getStringExtra(AlarmScheduler.EXTRA_PACKAGE_NAME) ?: return
        val app = context.applicationContext as BlockApplication
        val pendingResult = goAsync()
        app.applicationScope.launch {
            app.repository.expireDuePackage(packageName)
            pendingResult.finish()
        }
    }
}
