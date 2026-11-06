package app.privateinbox.attachments

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import app.privateinbox.security.SafeLog
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Copies an externally-provided attachment into app-private storage, enforcing
 * [AttachmentPolicy] (URI shape, confused-deputy source check, magic bytes, size caps).
 * Only called after a user action (photo picker result, or tapping "Attach" on a shared photo);
 * nothing downstream ever reads the foreign URI again.
 */
class AttachmentImporter(private val context: Context) {

    data class Imported(val file: File, val type: ImageType, val sizeBytes: Long)

    private val dir: File get() = File(context.noBackupFilesDir, "attachments").apply { mkdirs() }

    val ownAuthorities: Set<String> get() = setOf("${context.packageName}.fileprovider")

    /** Android user ID of this process (UserHandle.getUserId(uid) = uid / PER_USER_RANGE). */
    val currentUserId: Int get() = android.os.Process.myUid() / PER_USER_RANGE

    /** Resolves a bare name produced by [import]; rejects anything path-like. */
    fun file(name: String): File? =
        name.takeIf { Regex("^[0-9a-f\\-]{36}\\.[a-z]{3,4}$").matches(it) }?.let { File(dir, it) }?.takeIf { it.isFile }

    /**
     * Cheap check (no I/O on the content) of whether [uriString] may be imported.
     * [grantedByCaller] = the URI was in the sender's ClipData with FLAG_GRANT_READ_URI_PERMISSION,
     * or came back from a system picker the user drove.
     */
    fun check(uriString: String, grantedByCaller: Boolean): UriDecision {
        val shape = AttachmentPolicy.checkUri(uriString, ownAuthorities, currentUserId)
        if (shape != UriDecision.Accept) return shape
        val authority = AttachmentPolicy.canonicalAuthority(uriString, currentUserId)
            ?: return UriDecision.Reject(UriDecision.Reason.MALFORMED)
        // Defence in depth: the authority ContentResolver will route to (decoded, user prefix
        // stripped) must be exactly the one we validated and resolve below.
        val routed = Uri.parse(uriString).authority?.substringAfterLast('@')
        if (routed != authority) return UriDecision.Reject(UriDecision.Reason.MALFORMED)
        val pi = runCatching { context.packageManager.resolveContentProvider(authority, 0) }.getOrNull()
            ?: return UriDecision.Reject(UriDecision.Reason.UNRESOLVED_PROVIDER)
        if (pi.packageName == context.packageName) return UriDecision.Reject(UriDecision.Reason.OWN_PROVIDER)
        val facts = AttachmentPolicy.ProviderFacts(
            exported = pi.exported,
            readPermission = pi.readPermission,
            pathReadPermissions = pi.pathPermissions.orEmpty().mapNotNull { it.readPermission },
        )
        return AttachmentPolicy.checkSource(facts, ::weHold, grantedByCaller)
    }

    private fun weHold(permission: String) =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Deletes draft copies older than [MAX_AGE_MS] (scheduled photos live in the MMS store, not here). */
    fun sweep(now: Long = System.currentTimeMillis()) {
        dir.listFiles().orEmpty().filter { now - it.lastModified() > MAX_AGE_MS }.forEach { it.delete() }
    }

    /** Keeps the folder under [AttachmentPolicy.MAX_STORE_BYTES] by evicting the oldest copies. */
    private fun makeRoom(incoming: Long) {
        sweep()
        val files = dir.listFiles().orEmpty().sortedBy { it.lastModified() }.toMutableList()
        var total = files.sumOf { it.length() }
        while (files.isNotEmpty() && total + incoming > AttachmentPolicy.MAX_STORE_BYTES) {
            val f = files.removeAt(0)
            total -= f.length()
            f.delete()
        }
    }

    /** Blocking; call off the main thread. Returns null when rejected. */
    fun import(uriString: String, grantedByCaller: Boolean): Imported? {
        val decision = check(uriString, grantedByCaller)
        if (decision != UriDecision.Accept) {
            SafeLog.w(TAG, "attachment source refused: ${(decision as UriDecision.Reject).reason}")
            return null
        }
        val uri = Uri.parse(uriString)
        makeRoom(AttachmentPolicy.MAX_BYTES)
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
        } catch (e: RuntimeException) {
            // Hostile providers can throw anything from openInputStream/read.
            out.delete()
            SafeLog.w(TAG, "attachment import failed: ${e.javaClass.simpleName}")
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
        const val MAX_AGE_MS = 12L * 60 * 60 * 1000
        const val PER_USER_RANGE = 100_000
    }
}
