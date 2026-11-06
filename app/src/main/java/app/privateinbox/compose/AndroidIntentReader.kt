package app.privateinbox.compose

import android.content.Intent
import android.net.Uri
import android.os.Build

/** Converts a real Intent into the platform-free [ExternalIntent] that [IntentTriage] checks. */
object AndroidIntentReader {

    fun read(intent: Intent): ExternalIntent {
        val extras = intent.extras
        // Reading extras from a foreign Intent can throw (malformed Parcel / unknown class).
        val keys = runCatching { extras?.keySet()?.toSet() }.getOrNull().orEmpty()
        return ExternalIntent(
            action = intent.action,
            dataUri = intent.dataString,
            mimeType = intent.type,
            extraText = runCatching { intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() }.getOrNull(),
            extraSmsBody = runCatching { intent.getStringExtra("sms_body") }.getOrNull(),
            extraEmails = emptyList(),
            streamUris = runCatching { streams(intent) }.getOrDefault(emptyList()).map(Uri::toString),
            extraKeys = keys,
        )
    }

    private fun streams(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            },
        )
        Intent.ACTION_SEND_MULTIPLE ->
            if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            }
        else -> emptyList()
    }
}
