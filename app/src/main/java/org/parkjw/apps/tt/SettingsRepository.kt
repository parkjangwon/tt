package org.parkjw.apps.tt

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "tt_settings")

enum class ActionType { APP, SHORTCUT }

data class Settings(
    val enabled: Boolean = false,
    val actionType: ActionType = ActionType.APP,
    val packageName: String = "",
    val appLabel: String = "",
    val shortcutPackage: String = "",
    val shortcutLabel: String = "",
    val shortcutId: String = "",
) {
    val isActionReady: Boolean
        get() = when (actionType) {
            ActionType.APP -> packageName.isNotBlank()
            ActionType.SHORTCUT -> shortcutPackage.isNotBlank() && shortcutId.isNotBlank()
        }
}

class SettingsRepository(context: Context) {

    private val dataStore = context.applicationContext.dataStore

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val ACTION_TYPE = stringPreferencesKey("action_type")
        val PACKAGE_NAME = stringPreferencesKey("package_name")
        val APP_LABEL = stringPreferencesKey("app_label")
        val SHORTCUT_PACKAGE = stringPreferencesKey("shortcut_package")
        val SHORTCUT_LABEL = stringPreferencesKey("shortcut_label")
        val SHORTCUT_ID = stringPreferencesKey("shortcut_id")
    }

    val settings: Flow<Settings> = dataStore.data.map { p ->
        Settings(
            enabled = p[Keys.ENABLED] ?: false,
            actionType = runCatching {
                ActionType.valueOf(p[Keys.ACTION_TYPE] ?: ActionType.APP.name)
            }.getOrDefault(ActionType.APP),
            packageName = p[Keys.PACKAGE_NAME] ?: "",
            appLabel = p[Keys.APP_LABEL] ?: "",
            shortcutPackage = p[Keys.SHORTCUT_PACKAGE] ?: "",
            shortcutLabel = p[Keys.SHORTCUT_LABEL] ?: "",
            shortcutId = p[Keys.SHORTCUT_ID] ?: "",
        )
    }

    suspend fun setEnabled(value: Boolean) = edit { it[Keys.ENABLED] = value }

    suspend fun setActionType(value: ActionType) = edit { it[Keys.ACTION_TYPE] = value.name }

    suspend fun setApp(pkg: String, label: String) = edit {
        it[Keys.PACKAGE_NAME] = pkg
        it[Keys.APP_LABEL] = label
    }

    suspend fun setShortcut(pkg: String, appLabel: String, shortcutId: String, shortcutLabel: String) = edit {
        it[Keys.SHORTCUT_PACKAGE] = pkg
        it[Keys.SHORTCUT_LABEL] = shortcutLabel
        it[Keys.SHORTCUT_ID] = shortcutId
        it[Keys.APP_LABEL] = appLabel
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }
}
