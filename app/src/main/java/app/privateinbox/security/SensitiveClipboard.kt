package app.privateinbox.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import java.util.UUID

/**
 * Copies OTPs / message text to the clipboard flagged as sensitive (hidden from the clipboard
 * preview on API 33+) and, for codes, clears it again after [OTP_CLEAR_MS].
 *
 * Clearing is best-effort (in-process timer). If we can still read the clip description we only
 * clear our own clip; while backgrounded Android 10+ hides the description from us, and we clear
 * anyway: losing a just-copied unrelated clip is the safer failure than leaving a code behind.
 */
object SensitiveClipboard {
    private const val LEGACY_SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"
    const val OTP_CLEAR_MS = 60_000L
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun copy(context: Context, label: String, value: String, clearAfterMs: Long? = if (label == "OTP") OTP_CLEAR_MS else null) {
        val tag = "$label#${UUID.randomUUID().toString().take(8)}"
        val clip = ClipData.newPlainText(tag, value)
        clip.description.extras = PersistableBundle().apply {
            val key = if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else LEGACY_SENSITIVE_EXTRA
            putBoolean(key, true)
        }
        val cm = context.applicationContext.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(clip)
        if (clearAfterMs != null) main.postDelayed({ clearIfOurs(cm, tag) }, clearAfterMs)
    }

    private fun clearIfOurs(cm: ClipboardManager, tag: String) {
        runCatching {
            val label = cm.primaryClipDescription?.label?.toString()
            if (label != null && label != tag) return // user copied something else since
            if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}
