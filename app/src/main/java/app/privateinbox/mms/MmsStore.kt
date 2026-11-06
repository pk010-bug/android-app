package app.privateinbox.mms

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Private on-disk store for MMS media: noBackupFilesDir/mms (never shared storage, excluded from
 * Android cloud backup). Rows in mms_parts hold only a bare file name, validated on every read so a
 * tampered row can't point outside this directory.
 */
class MmsStore(context: Context) {
    val dir: File = File(context.noBackupFilesDir, "mms").apply { mkdirs() }

    /** Scratch PDUs exchanged with the system MMS service via our FileProvider (cache/mms). */
    val pduDir: File = File(context.cacheDir, "mms").apply { mkdirs() }

    fun save(bytes: ByteArray, extension: String): String {
        val name = "${UUID.randomUUID()}.${extension.filter(Char::isLetterOrDigit).take(5)}"
        File(dir, name).writeBytes(bytes)
        return name
    }

    fun file(name: String?): File? = name?.takeIf { SAFE_NAME.matches(it) }?.let { File(dir, it) }?.takeIf { it.isFile }

    fun delete(name: String?) { file(name)?.delete() }

    fun newPduFile(prefix: String): File = File(pduDir, "$prefix-${UUID.randomUUID()}.pdu")

    fun pduFile(name: String?): File? = name?.takeIf { SAFE_PDU.matches(it) }?.let { File(pduDir, it) }

    /** Remove scratch PDUs older than a day (download/send callbacks that never arrived). */
    fun sweepPdus(now: Long = System.currentTimeMillis()) {
        pduDir.listFiles()?.filter { now - it.lastModified() > 24 * 3600_000L }?.forEach { it.delete() }
    }

    companion object {
        private val SAFE_NAME = Regex("^[0-9a-f\\-]{36}\\.[a-z0-9]{1,5}$")
        private val SAFE_PDU = Regex("^(dl|send|ack)-[0-9a-f\\-]{36}\\.pdu$")
        const val MAX_PDU_BYTES = 8 * 1024 * 1024
    }
}
