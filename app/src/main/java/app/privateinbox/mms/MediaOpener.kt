package app.privateinbox.mms

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import app.privateinbox.attachments.MediaPolicy
import java.io.File
import java.util.UUID

/**
 * Explicit, user-initiated "Open with…" for received media. Never called automatically.
 * A short-lived copy goes into cache/open (the only FileProvider path for this); the receiving
 * app gets a one-off READ grant for that single URI. Copies are swept after an hour.
 */
object MediaOpener {
    private const val TTL_MS = 3600_000L

    fun open(context: Context, store: MmsStore, fileName: String?): Boolean {
        val src = store.file(fileName) ?: return false
        val type = MediaPolicy.forExtension(src.extension) ?: return false
        val dir = File(context.cacheDir, "open").apply { mkdirs() }
        sweep(dir)
        val copy = File(dir, "${UUID.randomUUID()}.${type.extension}")
        src.copyTo(copy)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", copy)
        val view = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, type.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(Intent.createChooser(view, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: ActivityNotFoundException) {
            copy.delete()
            false
        }
    }

    fun sweep(dir: File, now: Long = System.currentTimeMillis()) {
        dir.listFiles()?.filter { now - it.lastModified() > TTL_MS }?.forEach { it.delete() }
    }
}
