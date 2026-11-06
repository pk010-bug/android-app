package app.privateinbox

import android.content.Context
import app.privateinbox.data.db.PrivateInboxDatabase
import app.privateinbox.data.prefs.SettingsStore
import app.privateinbox.data.repo.MessageRepository
import app.privateinbox.data.rules.AssetRuleLoader
import app.privateinbox.data.rules.RuleRepository
import app.privateinbox.data.rules.RuleSetParser
import app.privateinbox.data.telephony.TelephonyStore
import app.privateinbox.domain.Category
import app.privateinbox.notifications.MessageNotifier
import app.privateinbox.notifications.OtpNotifier
import app.privateinbox.sms.SimManager
import kotlinx.coroutines.flow.first

/** Manual DI. Everything is local; nothing here talks to a network. */
class AppContainer(private val context: Context) {
    val settings by lazy { SettingsStore(context) }
    val database by lazy { PrivateInboxDatabase.get(context) }
    val telephony by lazy { TelephonyStore(context) }
    val sims by lazy { SimManager(context) }
    val rules by lazy {
        val text = context.assets.open(AssetRuleLoader.DEFAULT_RULES_ASSET).bufferedReader().use { it.readText() }
        RuleRepository(RuleSetParser.parse(text), database.ruleDao())
    }
    val messages by lazy { MessageRepository(database, rules, telephony) }
    val backup by lazy { app.privateinbox.backup.BackupManager(database, rules, messages) }
    val messageNotifier by lazy { MessageNotifier(context) }
    val otpNotifier by lazy { OtpNotifier(context) }

    /** Full receive pipeline, used by SmsReceiver. */
    suspend fun receiveIncoming(address: String, body: String, timestamp: Long, subscriptionId: Int?) {
        val providerId = telephony.insertIncoming(address, body, timestamp, subscriptionId)
        val r = messages.ingestIncoming(address, body, timestamp, subscriptionId, providerId) ?: return
        if (r.blocked || r.muted || !settings.notificationsEnabled.first()) return
        if (r.category == Category.SPAM) return
        if (r.category == Category.PROMOTIONAL && !settings.notifyPromotions.first()) return

        val hideOtp = settings.hideOtpInNotifications.first()
        if (r.otpCode != null && !r.scam.isSuspicious) {
            otpNotifier.show(r.messageId.toInt(), r.conversationId, r.otpCode, hideOtp)
        } else {
            val text = if (hideOtp && r.otpCode != null) "Contains a code. Open to view." else body
            val warn = r.scam.isSuspicious && settings.scamWarnings.first()
            messageNotifier.showMessage(r.conversationId, r.displayName, text, warn)
        }
    }
}
