package app.privateinbox.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Local-only user preferences (DataStore, app-private, excluded from backup). */
class SettingsStore(private val context: Context) {

    private object Keys {
        val appLock = booleanPreferencesKey("app_lock_enabled")
        val lockTimeoutSec = intPreferencesKey("app_lock_timeout_sec")
        val hideOtpInNotifications = booleanPreferencesKey("hide_otp_in_notifications")
        val confirmBeforeSend = booleanPreferencesKey("confirm_before_send")
        val notifications = booleanPreferencesKey("notifications_enabled")
        val notifyPromotions = booleanPreferencesKey("notify_promotions")
        val recycleDays = intPreferencesKey("recycle_bin_days")
        val theme = stringPreferencesKey("theme_mode")
        val billReminders = booleanPreferencesKey("bill_reminders")
        val scamWarnings = booleanPreferencesKey("scam_warnings")
        val importDone = booleanPreferencesKey("provider_import_done")
        val lastImportedProviderId = longPreferencesKey("provider_last_id")
        val defaultSubId = intPreferencesKey("default_sub_id")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val widgetShowNames = booleanPreferencesKey("widget_show_names")
        val hideOnLockScreen = booleanPreferencesKey("hide_on_lock_screen")
        val integrityWarned = stringPreferencesKey("integrity_warned_signals")
        val rootNoticeDismissed = stringPreferencesKey("integrity_root_notice_dismissed")
        val contentFreeWhenLocked = booleanPreferencesKey("notifications_content_free_when_locked")
        /** RB-1: v1.0.3's "Auto-download MMS from contacts". No longer read; removed on upgrade. */
        val obsoleteMmsAutoKey = booleanPreferencesKey("mms_auto_download_contacts")
    }

    private val data get() = context.dataStore.data

    val appLockEnabled: Flow<Boolean> = data.map { it[Keys.appLock] ?: false }
    val lockTimeoutSec: Flow<Int> = data.map { it[Keys.lockTimeoutSec] ?: 30 }
    val hideOtpInNotifications: Flow<Boolean> = data.map { it[Keys.hideOtpInNotifications] ?: true }
    val confirmBeforeSend: Flow<Boolean> = data.map { it[Keys.confirmBeforeSend] ?: true }
    val notificationsEnabled: Flow<Boolean> = data.map { it[Keys.notifications] ?: true }
    val notifyPromotions: Flow<Boolean> = data.map { it[Keys.notifyPromotions] ?: false }
    val recycleBinDays: Flow<Int> = data.map { it[Keys.recycleDays] ?: 30 }
    val themeMode: Flow<ThemeMode> = data.map { p -> ThemeMode.entries.firstOrNull { it.name == p[Keys.theme] } ?: ThemeMode.SYSTEM }
    val billReminders: Flow<Boolean> = data.map { it[Keys.billReminders] ?: true }
    val scamWarnings: Flow<Boolean> = data.map { it[Keys.scamWarnings] ?: true }
    val importDone: Flow<Boolean> = data.map { it[Keys.importDone] ?: false }
    val lastImportedProviderId: Flow<Long> = data.map { it[Keys.lastImportedProviderId] ?: 0L }
    val defaultSubId: Flow<Int?> = data.map { it[Keys.defaultSubId] }
    val onboardingDone: Flow<Boolean> = data.map { it[Keys.onboardingDone] ?: false }
    /** Off by default: the widget shows counts only. Never shown while app lock is on. */
    val widgetShowNames: Flow<Boolean> = data.map { it[Keys.widgetShowNames] ?: false }
    /**
     * On by default: message / bill / OTP notifications use VISIBILITY_SECRET, so nothing appears on
     * the lock screen even if the phone is set to "show sensitive content".
     */
    val hideOnLockScreen: Flow<Boolean> = data.map { it[Keys.hideOnLockScreen] ?: true }

    /**
     * F-08, on by default: while app lock is on, notifications say only "New message" (no sender, no
     * text, no code), so nothing readable shows in the shade of an unlocked phone either.
     */
    val contentFreeWhenLocked: Flow<Boolean> = data.map { it[Keys.contentFreeWhenLocked] ?: true }

    /** Signal set (names only) the one-time high-risk integrity warning was last shown for. */
    val integrityWarnedSignals: Flow<String> = data.map { it[Keys.integrityWarned] ?: "" }

    /** Root signal set (names only) the user dismissed the informational notice for. */
    val rootNoticeDismissedSignals: Flow<String> = data.map { it[Keys.rootNoticeDismissed] ?: "" }

    /** FLAG_SECURE on OTP / finance / conversation screens whenever either protection is on. */
    val secureScreens: Flow<Boolean> =
        combine(appLockEnabled, hideOtpInNotifications) { lock, hide -> lock || hide }

    suspend fun setAppLock(v: Boolean) = put(Keys.appLock, v)
    suspend fun setLockTimeoutSec(v: Int) = put(Keys.lockTimeoutSec, v.coerceIn(0, 3600))
    suspend fun setHideOtpInNotifications(v: Boolean) = put(Keys.hideOtpInNotifications, v)
    suspend fun setConfirmBeforeSend(v: Boolean) = put(Keys.confirmBeforeSend, v)
    suspend fun setNotificationsEnabled(v: Boolean) = put(Keys.notifications, v)
    suspend fun setNotifyPromotions(v: Boolean) = put(Keys.notifyPromotions, v)
    suspend fun setRecycleBinDays(v: Int) = put(Keys.recycleDays, v.coerceIn(1, 365))
    suspend fun setThemeMode(v: ThemeMode) = put(Keys.theme, v.name)
    suspend fun setBillReminders(v: Boolean) = put(Keys.billReminders, v)
    suspend fun setScamWarnings(v: Boolean) = put(Keys.scamWarnings, v)
    suspend fun setImportDone(v: Boolean) = put(Keys.importDone, v)
    suspend fun setLastImportedProviderId(v: Long) = put(Keys.lastImportedProviderId, v)
    suspend fun setOnboardingDone(v: Boolean) = put(Keys.onboardingDone, v)
    suspend fun setWidgetShowNames(v: Boolean) = put(Keys.widgetShowNames, v)
    suspend fun setHideOnLockScreen(v: Boolean) = put(Keys.hideOnLockScreen, v)
    suspend fun setIntegrityWarnedSignals(v: String) = put(Keys.integrityWarned, v)
    suspend fun setRootNoticeDismissedSignals(v: String) = put(Keys.rootNoticeDismissed, v)
    suspend fun setContentFreeWhenLocked(v: Boolean) = put(Keys.contentFreeWhenLocked, v)

    /**
     * RB-1: MMS is downloaded only on a tap, so the v1.0.3 auto-download preference is meaningless;
     * nothing reads it any more, and this deletes a stored value (on or off) on upgrade.
     */
    suspend fun dropLegacyMmsAutoDownload() {
        context.dataStore.edit { it.remove(Keys.obsoleteMmsAutoKey) }
    }
    suspend fun setDefaultSubId(v: Int?) {
        context.dataStore.edit { if (v == null) it.remove(Keys.defaultSubId) else it[Keys.defaultSubId] = v }
    }

    suspend fun <T> now(flow: Flow<T>): T = flow.first()

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}
