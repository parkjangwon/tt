package org.parkjw.apps.tt

import android.content.Context
import android.content.Intent

/** Shared launch helpers used by both the service and the settings UI. */
object ActionLauncher {

    /**
     * Builds the launch intent for the configured action, or null if it cannot be resolved.
     * Shortcuts are re-resolved at launch time so app updates are picked up automatically.
     */
    fun buildIntent(context: Context, settings: Settings): Intent? = when (settings.actionType) {
        ActionType.APP -> context.packageManager.getLaunchIntentForPackage(settings.packageName)
        ActionType.SHORTCUT ->
            ShortcutReader.shortcutsFor(context, settings.shortcutPackage)
                .firstOrNull { it.id == settings.shortcutId }?.intent
    }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun describe(settings: Settings): String = when (settings.actionType) {
        ActionType.APP -> settings.appLabel.ifBlank { settings.packageName }
        ActionType.SHORTCUT -> settings.shortcutLabel.ifBlank { settings.shortcutId }
    }
}
