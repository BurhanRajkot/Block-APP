package com.blockapp.android.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.blockapp.android.MainActivity

class BlockDeviceAdminReceiver : DeviceAdminReceiver() {

    /**
     * Text shown inside the system Device Admin deactivation confirmation dialog.
     * This is the user's last warning before they can confirm the deactivation.
     */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        // Also bring the app to the foreground so the user is confronted with the
        // lock state and the unlock key option before they can dismiss this dialog.
        val appIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(appIntent)

        return "⚠️ WARNING: Disabling Device Admin removes ALL protection from this app and " +
            "will allow it to be uninstalled immediately. If a lock is still active, " +
            "the only intended way to end it early is an unlock key from the developer. " +
            "Are you absolutely sure you want to disable protection?"
    }

    /**
     * Called when admin has been successfully disabled. Re-request admin immediately
     * to make it as hard as possible to keep admin revoked.
     */
    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(
            context,
            "Device Admin was removed — protection is now off. Tap 'Activate Device Admin' in the app to restore it.",
            Toast.LENGTH_LONG,
        ).show()
        // Bring the app forward so the user can re-grant admin
        val appIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(appIntent)
    }
}
