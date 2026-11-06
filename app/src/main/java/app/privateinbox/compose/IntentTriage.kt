package app.privateinbox.compose

import app.privateinbox.attachments.AttachmentPolicy
import app.privateinbox.attachments.UriDecision
import java.net.URLDecoder

/**
 * Platform-free snapshot of an Intent delivered to us by another app.
 * Built by [AndroidIntentReader]; kept free of android.* so it is unit-testable.
 */
data class ExternalIntent(
    val action: String?,
    val dataUri: String? = null,
    val mimeType: String? = null,
    val extraText: String? = null,
    /** Intent.EXTRA_TEXT is preferred; "sms_body" is the legacy extra many apps use. */
    val extraSmsBody: String? = null,
    val extraEmails: List<String> = emptyList(),
    val streamUris: List<String> = emptyList(),
    /** Names of all extras present, used only to detect (and drop) auto-send requests. */
    val extraKeys: Set<String> = emptySet(),
)

/**
 * What an external intent is allowed to become: a DRAFT shown in the composer.
 * There is intentionally no "send" field. A human must press Send in our UI.
 */
data class ComposePrefill(
    val recipients: List<String>,
    val body: String,
    val attachmentUris: List<String>,
    val rejectedAttachments: List<Pair<String, UriDecision.Reject>> = emptyList(),
    /** True if the caller tried to request automatic sending; we ignored it. */
    val autoSendIgnored: Boolean = false,
)

sealed interface TriageResult {
    /** Open the composer with this draft. Never sends. */
    data class Prefill(val prefill: ComposePrefill) : TriageResult

    /** Normal launch (launcher icon etc.). */
    data object OpenApp : TriageResult

    /** Malformed / unsupported. Show the inbox and do nothing else. */
    data class Ignore(val reason: String) : TriageResult
}

/**
 * RULE: "Never send without a tap."
 *
 * Every external entry point (sms:/smsto:/mms:/mmsto:, ACTION_SEND, ACTION_SENDTO, share sheet)
 * goes through [triage], which can only ever produce a draft. Sending lives in
 * [app.privateinbox.sms.SmsSender], which requires a [app.privateinbox.sms.UserSendConfirmation]
 * created by the Send button in the composer UI. A source-scan unit test enforces that only
 * ui/compose calls SmsSender.
 */
object IntentTriage {

    const val ACTION_MAIN = "android.intent.action.MAIN"
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"
    const val ACTION_SENDTO = "android.intent.action.SENDTO"

    private val SMS_SCHEMES = setOf("sms", "smsto", "mms", "mmsto")

    /** Extras that some apps use to ask an SMS app to send immediately. We drop them all. */
    val AUTO_SEND_EXTRAS = setOf(
        "send", "auto_send", "autosend", "send_immediately", "exit_on_sent",
        "android.intent.extra.SEND_NOW", "compose_mode",
    )

    const val MAX_BODY_CHARS = 5_000
    const val MAX_RECIPIENTS = 20
    const val MAX_ATTACHMENTS = 10

    private val RECIPIENT = Regex("^\\+?[0-9A-Za-z .()\\-]{2,32}$")

    /** [currentUserId]: our Android user; only that `N@` prefix is tolerated on shared URIs (null = none). */
    fun triage(intent: ExternalIntent, ownAuthorities: Set<String>, currentUserId: Int? = null): TriageResult {
        val autoSendIgnored = intent.extraKeys.any { k -> AUTO_SEND_EXTRAS.any { it.equals(k, ignoreCase = true) } }
        return when (intent.action) {
            null, ACTION_MAIN -> TriageResult.OpenApp
            ACTION_SENDTO, ACTION_VIEW -> fromSmsUri(intent, ownAuthorities, currentUserId, autoSendIgnored)
            ACTION_SEND, ACTION_SEND_MULTIPLE ->
                if (intent.dataUri != null && schemeOf(intent.dataUri) in SMS_SCHEMES) {
                    fromSmsUri(intent, ownAuthorities, currentUserId, autoSendIgnored)
                } else {
                    TriageResult.Prefill(build(emptyList(), intent, ownAuthorities, currentUserId, autoSendIgnored, uriBody = null))
                }
            else -> TriageResult.Ignore("unsupported action")
        }
    }

    private fun fromSmsUri(intent: ExternalIntent, own: Set<String>, userId: Int?, autoSend: Boolean): TriageResult {
        val uri = intent.dataUri ?: return TriageResult.Ignore("missing data uri")
        val scheme = schemeOf(uri) ?: return TriageResult.Ignore("missing scheme")
        if (scheme !in SMS_SCHEMES) return TriageResult.Ignore("unsupported scheme")

        val ssp = uri.substringAfter(':')
        val recipientPart = ssp.substringBefore('?')
        val query = ssp.substringAfter('?', missingDelimiterValue = "")
        val uriBody = queryParam(query, "body")

        val recipients = decode(recipientPart)
            .split(',', ';')
            .map { it.trim().removePrefix("//") }
            .filter { it.isNotEmpty() }
        return TriageResult.Prefill(build(recipients, intent, own, userId, autoSend, uriBody))
    }

    private fun build(
        recipients: List<String>,
        intent: ExternalIntent,
        own: Set<String>,
        userId: Int?,
        autoSend: Boolean,
        uriBody: String?,
    ): ComposePrefill {
        val body = (intent.extraSmsBody ?: intent.extraText ?: uriBody ?: "")
            .take(MAX_BODY_CHARS)
        val accepted = mutableListOf<String>()
        val rejected = mutableListOf<Pair<String, UriDecision.Reject>>()
        intent.streamUris.take(MAX_ATTACHMENTS).forEach { u ->
            when (val d = AttachmentPolicy.checkUri(u, own, userId)) {
                UriDecision.Accept -> accepted += u
                is UriDecision.Reject -> rejected += u to d
            }
        }
        return ComposePrefill(
            recipients = recipients.filter { RECIPIENT.matches(it) }.distinct().take(MAX_RECIPIENTS),
            body = body,
            attachmentUris = accepted,
            rejectedAttachments = rejected,
            autoSendIgnored = autoSend,
        )
    }

    private fun schemeOf(uri: String): String? =
        uri.substringBefore(':', missingDelimiterValue = "").lowercase().takeIf { it.isNotEmpty() }

    private fun queryParam(query: String, name: String): String? =
        query.split('&').firstNotNullOfOrNull { kv ->
            val k = kv.substringBefore('=')
            if (k.equals(name, ignoreCase = true)) decode(kv.substringAfter('=', "")) else null
        }

    private fun decode(s: String): String =
        try {
            URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            s
        }
}
