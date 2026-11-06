package app.privateinbox.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Local-only user preferences (DataStore). */
class SettingsStore(private val context: Context) {

    private object Keys {
        val appLock = booleanPreferencesKey("app_lock_enabled")
        val hideOtpInNotifications = booleanPreferencesKey("hide_otp_in_notifications")
    }

    val appLockEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.appLock] ?: false }
    val hideOtpInNotifications: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.hideOtpInNotifications] ?: true }

    /** FLAG_SECURE on OTP / finance / conversation screens whenever either protection is on. */
    val secureScreens: Flow<Boolean> =
        combine(appLockEnabled, hideOtpInNotifications) { lock, hide -> lock || hide }

    suspend fun setAppLock(enabled: Boolean) {
        context.dataStore.edit { it[Keys.appLock] = enabled }
    }

    suspend fun setHideOtpInNotifications(enabled: Boolean) {
        context.dataStore.edit { it[Keys.hideOtpInNotifications] = enabled }
    }
}
