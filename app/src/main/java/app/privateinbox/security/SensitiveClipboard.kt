package app.privateinbox.security

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/** Copies OTPs to the clipboard flagged as sensitive (hidden from the clipboard preview on API 33+). */
object SensitiveClipboard {
    private const val LEGACY_SENSITIVE_EXTRA = "android.content.extra.IS_SENSITIVE"

    fun copy(context: Context, label: String, value: String) {
        val clip = ClipData.newPlainText(label, value)
        clip.description.extras = PersistableBundle().apply {
            val key = if (Build.VERSION.SDK_INT >= 33) ClipDescription.EXTRA_IS_SENSITIVE else LEGACY_SENSITIVE_EXTRA
            putBoolean(key, true)
        }
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
    }
}
