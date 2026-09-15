package org.parkjw.apps.tt

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.drawable.Drawable
import android.net.Uri

/**
 * Reads another app's *static* (manifest) shortcuts by parsing the shortcut XML that the
 * app publishes in its own APK — the same data the launcher uses to show long-press
 * shortcuts. Dynamic and pinned shortcuts are invisible to third-party apps, so only
 * manifest shortcuts can be offered here.
 */
object ShortcutReader {

    data class AppShortcut(
        val id: String,
        val label: String,
        val icon: Drawable?,
        val intent: Intent,
    )

    private const val SHORTCUTS_META = "android.app.shortcuts"
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    fun shortcutsFor(context: Context, pkg: String): List<AppShortcut> = runCatching {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setPackage(pkg)
        val result = ArrayList<AppShortcut>()
        for (ri in pm.queryIntentActivities(launcherIntent, PackageManager.GET_META_DATA)) {
            val resId = ri.activityInfo.metaData?.getInt(SHORTCUTS_META) ?: 0
            if (resId == 0) continue
            val res = pm.getResourcesForApplication(pkg)
            result += parseShortcuts(res.getXml(resId), res, pkg)
        }
        result.distinctBy { it.id }
    }.getOrDefault(emptyList())

    private fun parseShortcuts(parser: XmlResourceParser, res: Resources, pkg: String): List<AppShortcut> {
        val out = ArrayList<AppShortcut>()
        var current: PendingShortcut? = null
        var inIntent = false
        try {
            var event = parser.eventType
            while (event != XmlResourceParser.END_DOCUMENT) {
                when (event) {
                    XmlResourceParser.START_TAG -> when (parser.name) {
                        "shortcut" -> {
                            val enabled = parser.getAttributeBooleanValue(ANDROID_NS, "enabled", true)
                            val id = parser.getAttributeValue(ANDROID_NS, "shortcutId")
                            if (enabled && id != null) {
                                current = PendingShortcut(
                                    id = id,
                                    label = resolveString(parser, res, pkg, "shortcutShortLabel")
                                        .ifBlank { id },
                                    icon = resolveIcon(parser, res, pkg),
                                )
                            }
                        }

                        "intent" -> if (current != null && !inIntent) {
                            inIntent = true
                            current.action = parser.getAttributeValue(ANDROID_NS, "action")
                            current.data = parser.getAttributeValue(ANDROID_NS, "data")
                            current.mimeType = parser.getAttributeValue(ANDROID_NS, "mimeType")
                            current.targetPackage = parser.getAttributeValue(ANDROID_NS, "targetPackage")
                            current.targetClass = parser.getAttributeValue(ANDROID_NS, "targetClass")
                        }

                        "categories" -> if (current != null && inIntent) {
                            parser.getAttributeValue(ANDROID_NS, "name")?.let { current.categories.add(it) }
                        }

                        "extra" -> if (current != null && inIntent) {
                            val name = parser.getAttributeValue(ANDROID_NS, "name")
                            val value = parser.getAttributeValue(ANDROID_NS, "value")
                            if (name != null && value != null) current.extras[name] = value
                        }
                    }

                    XmlResourceParser.END_TAG -> when (parser.name) {
                        "intent" -> inIntent = false
                        "shortcut" -> {
                            current?.let { pending ->
                                if (pending.action != null) out.add(pending.build(pkg))
                            }
                            current = null
                        }
                    }
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return out
    }

    private class PendingShortcut(
        val id: String,
        val label: String,
        val icon: Drawable?,
    ) {
        var action: String? = null
        var data: String? = null
        var mimeType: String? = null
        var targetPackage: String? = null
        var targetClass: String? = null
        val categories = mutableListOf<String>()
        val extras = mutableMapOf<String, String>()

        fun build(pkg: String): AppShortcut {
            val intent = Intent(action)
            data?.let { intent.data = Uri.parse(it) }
            mimeType?.let { intent.type = it }
            if (targetClass != null) {
                val cls = if (targetClass!!.startsWith(".")) pkg + targetClass else targetClass!!
                intent.setClassName(targetPackage ?: pkg, cls)
            } else {
                intent.setPackage(targetPackage ?: pkg)
            }
            categories.forEach { intent.addCategory(it) }
            extras.forEach { (name, value) -> intent.putExtra(name, value) }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return AppShortcut(id, label, icon, intent)
        }
    }

    /** Resolves android:icon ("@drawable/x", "@mipmap/x" or "@<id>") to a Drawable. */
    private fun resolveIcon(parser: XmlResourceParser, res: Resources, pkg: String): Drawable? {
        val value = parser.getAttributeValue(ANDROID_NS, "icon") ?: return null
        if (!value.startsWith("@")) return null
        val ref = value.substring(1)
        val id = ref.toIntOrNull() ?: run {
            val type = ref.substringBefore('/')
            val name = ref.substringAfter('/')
            res.getIdentifier(name, type, pkg)
        }
        if (id == 0) return null
        return runCatching { res.getDrawable(id, null) }.getOrNull()
    }

    private fun resolveString(
        parser: XmlResourceParser,
        res: Resources,
        pkg: String,
        attr: String,
    ): String {
        val value = parser.getAttributeValue(ANDROID_NS, attr) ?: return ""
        if (!value.startsWith("@")) return value
        // "@2131953696" is a direct resource id; "@string/name" is an entry name.
        val ref = value.substring(1)
        val id = ref.toIntOrNull() ?: res.getIdentifier(ref.substringAfter('/'), "string", pkg)
        return if (id != 0) runCatching { res.getString(id) }.getOrDefault(value) else value
    }
}
