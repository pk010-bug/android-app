package app.privateinbox.attachments

import android.content.Context
import android.net.Uri
import app.privateinbox.security.SafeLog
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Copies an externally-provided attachment into app-private storage immediately, enforcing
 * [AttachmentPolicy]. Nothing downstream ever reads the foreign URI again.
 */
class AttachmentImporter(private val context: Context) {

    data class Imported(val file: File, val type: ImageType, val sizeBytes: Long)

    private val dir: File get() = File(context.noBackupFilesDir, "attachments").apply { mkdirs() }

    val ownAuthorities: Set<String> get() = setOf("${context.packageName}.fileprovider")

    /** Resolves a bare name produced by [import]; rejects anything path-like. */
    fun file(name: String): File? =
        name.takeIf { Regex("^[0-9a-f\\-]{36}\\.[a-z]{3,4}$").matches(it) }?.let { File(dir, it) }?.takeIf { it.isFile }

    /** Blocking; call off the main thread. Returns null when rejected. */
    fun import(uriString: String): Imported? {
        if (AttachmentPolicy.checkUri(uriString, ownAuthorities) != UriDecision.Accept) return null
        val uri = Uri.parse(uriString)
        val out = File(dir, UUID.randomUUID().toString())
        return try {
            val (head, size) = copyCapped(uri, out)
            when (val d = AttachmentPolicy.checkContent(head, size)) {
                is ContentDecision.Accept -> {
                    val named = File(dir, "${out.name}.${d.type.extension}")
                    if (!out.renameTo(named)) throw IOException("rename failed")
                    Imported(named, d.type, size)
                }
                is ContentDecision.Reject -> {
                    out.delete()
                    SafeLog.w(TAG, "attachment rejected: ${d.reason}")
                    null
                }
            }
        } catch (e: IOException) {
            out.delete()
            SafeLog.w(TAG, "attachment import failed: ${e.javaClass.simpleName}")
            null
        } catch (e: SecurityException) {
            out.delete()
            SafeLog.w(TAG, "attachment permission denied")
            null
        }
    }

    /** Streams at most MAX_BYTES + 1 bytes so an oversized/endless stream cannot fill the disk. */
    private fun copyCapped(uri: Uri, out: File): Pair<ByteArray, Long> {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("no stream")
        val head = ByteArray(HEAD_BYTES)
        var headLen = 0
        var total = 0L
        input.use { ins ->
            out.outputStream().use { os ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    if (headLen < HEAD_BYTES) {
                        val c = minOf(n, HEAD_BYTES - headLen)
                        System.arraycopy(buf, 0, head, headLen, c)
                        headLen += c
                    }
                    total += n
                    if (total > AttachmentPolicy.MAX_BYTES) return head to total // rejected by caller
                    os.write(buf, 0, n)
                }
            }
        }
        return head.copyOf(headLen) to total
    }

    private companion object {
        const val TAG = "AttachmentImporter"
        const val HEAD_BYTES = 32
    }
}
