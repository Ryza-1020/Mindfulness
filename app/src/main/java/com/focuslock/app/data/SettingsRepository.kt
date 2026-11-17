package com.focuslock.app.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

enum class CallsPolicy { NONE, STARRED, CONTACTS }
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** All user settings. Defaults favour maximum focus. */
data class AppSettings(
    val lastDurationMin: Int = 25,
    val blockNotifications: Boolean = true,
    val callsPolicy: CallsPolicy = CallsPolicy.STARRED,
    val allowRepeatCallers: Boolean = true,
    /** Default: silence stays on while paused, so a pause doesn't turn into a distraction. */
    val keepSilenceDuringPause: Boolean = true,
    val screenPinning: Boolean = true,
    val keepScreenOn: Boolean = true,
    val dimScreen: Boolean = true,
    val autoStartScheduled: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
)

private val Context.settingsStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore

    private object Keys {
        val lastDuration = intPreferencesKey("last_duration_min")
        val blockNotifications = booleanPreferencesKey("block_notifications")
        val callsPolicy = stringPreferencesKey("calls_policy")
        val repeatCallers = booleanPreferencesKey("repeat_callers")
        val keepSilencePaused = booleanPreferencesKey("keep_silence_paused")
        val pinning = booleanPreferencesKey("screen_pinning")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val dim = booleanPreferencesKey("dim_screen")
        val autoStart = booleanPreferencesKey("auto_start_scheduled")
        val theme = stringPreferencesKey("theme_mode")
    }

    val flow: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map(::read)

    suspend fun current(): AppSettings = flow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            lastDurationMin = p[Keys.lastDuration] ?: d.lastDurationMin,
            blockNotifications = p[Keys.blockNotifications] ?: d.blockNotifications,
            callsPolicy = p[Keys.callsPolicy]?.let { runCatching { CallsPolicy.valueOf(it) }.getOrNull() } ?: d.callsPolicy,
            allowRepeatCallers = p[Keys.repeatCallers] ?: d.allowRepeatCallers,
            keepSilenceDuringPause = p[Keys.keepSilencePaused] ?: d.keepSilenceDuringPause,
            screenPinning = p[Keys.pinning] ?: d.screenPinning,
            keepScreenOn = p[Keys.keepScreenOn] ?: d.keepScreenOn,
            dimScreen = p[Keys.dim] ?: d.dimScreen,
            autoStartScheduled = p[Keys.autoStart] ?: d.autoStartScheduled,
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
        )
    }

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[Keys.lastDuration] = s.lastDurationMin
        p[Keys.blockNotifications] = s.blockNotifications
        p[Keys.callsPolicy] = s.callsPolicy.name
        p[Keys.repeatCallers] = s.allowRepeatCallers
        p[Keys.keepSilencePaused] = s.keepSilenceDuringPause
        p[Keys.pinning] = s.screenPinning
        p[Keys.keepScreenOn] = s.keepScreenOn
        p[Keys.dim] = s.dimScreen
        p[Keys.autoStart] = s.autoStartScheduled
        p[Keys.theme] = s.themeMode.name
    }
}
