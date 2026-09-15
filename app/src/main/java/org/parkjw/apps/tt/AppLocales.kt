package org.parkjw.apps.tt

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Per-app language handling. Android 13+ uses the system per-app locale
 * (LocaleManager) which localizes every context including notifications;
 * API 30–32 falls back to a stored tag applied via attachBaseContext wrapping.
 */
object AppLocales {

    /** Blocking read — only used before onCreate (attachBaseContext) or at service start. */
    fun savedTagBlocking(context: Context): String = runCatching {
        runBlocking { SettingsRepository(context.applicationContext).settings.first().language }
    }.getOrDefault("")

    fun save(context: Context, tag: String) {
        CoroutineScope(Dispatchers.IO).launch {
            SettingsRepository(context.applicationContext).setLanguage(tag)
        }
    }

    /** Applies the tag system-wide (Android 13+); older levels apply it via wrap() + recreate(). */
    fun applySystem(context: Context, tag: String) {
        if (Build.VERSION.SDK_INT < 33) return
        context.getSystemService(LocaleManager::class.java)?.applicationLocales =
            if (tag.isBlank()) LocaleList.getEmptyLocaleList()
            else LocaleList.forLanguageTags(tag)
    }

    /** Wraps a base context with the stored locale (Android 12 and below only). */
    fun wrap(context: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return context
        val tag = savedTagBlocking(context)
        return if (tag.isBlank()) context else localized(context, tag)
    }

    fun localized(context: Context, tag: String): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        return context.createConfigurationContext(config)
    }
}
