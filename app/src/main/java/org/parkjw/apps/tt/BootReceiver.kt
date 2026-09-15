package org.parkjw.apps.tt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Restarts the listener after a reboot or app update if the user left it enabled. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val enabled = SettingsRepository(context.applicationContext)
                    .settings.first().enabled
                if (enabled) {
                    ContextCompat.startForegroundService(
                        context, Intent(context, HingeService::class.java)
                    )
                }
            } finally {
                result.finish()
            }
        }
    }
}
