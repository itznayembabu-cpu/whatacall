package com.example.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.whatacallPrefs: DataStore<Preferences> by preferencesDataStore(name = "whatacall_settings")

data class LocalAppSettings(
    val darkModePreference: String = "system",
    val chatWallpaper: String = "default",
    val notificationsEnabled: Boolean = true,
    val callRingtoneEnabled: Boolean = true,
    val lowDataCalls: Boolean = false
)

class SettingsDataStore(private val context: Context) {
    private val appContext = context.applicationContext

    private object Keys {
        val DARK_MODE = stringPreferencesKey("dark_mode_preference")
        val WALLPAPER = stringPreferencesKey("chat_wallpaper")
        val NOTIFICATIONS = booleanPreferencesKey("notifications_enabled")
        val RINGTONE = booleanPreferencesKey("call_ringtone_enabled")
        val LOW_DATA_CALLS = booleanPreferencesKey("low_data_calls")
    }

    val settingsFlow: Flow<LocalAppSettings> = appContext.whatacallPrefs.data.map { prefs ->
        LocalAppSettings(
            darkModePreference = prefs[Keys.DARK_MODE] ?: "system",
            chatWallpaper = prefs[Keys.WALLPAPER] ?: "default",
            notificationsEnabled = prefs[Keys.NOTIFICATIONS] ?: true,
            callRingtoneEnabled = prefs[Keys.RINGTONE] ?: true,
            lowDataCalls = prefs[Keys.LOW_DATA_CALLS] ?: false
        )
    }

    suspend fun setDarkMode(mode: String) {
        appContext.whatacallPrefs.edit { it[Keys.DARK_MODE] = mode }
    }

    suspend fun setChatWallpaper(wallpaper: String) {
        appContext.whatacallPrefs.edit { it[Keys.WALLPAPER] = wallpaper }
    }

    suspend fun setNotificationsEnabled(enabled: Boolean) {
        appContext.whatacallPrefs.edit { it[Keys.NOTIFICATIONS] = enabled }
    }

    suspend fun setCallRingtoneEnabled(enabled: Boolean) {
        appContext.whatacallPrefs.edit { it[Keys.RINGTONE] = enabled }
    }

    suspend fun setLowDataCalls(enabled: Boolean) {
        appContext.whatacallPrefs.edit { it[Keys.LOW_DATA_CALLS] = enabled }
    }
}
