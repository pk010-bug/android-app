package app.privateinbox.data.telephony

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import app.privateinbox.security.SafeLog
import app.privateinbox.sms.DefaultSmsApp
import java.util.concurrent.ConcurrentHashMap

/**
 * Bridge to the system SMS provider (the phone's canonical message store) and contacts.
 * Writes only happen when we are the default SMS app; Android rejects them otherwise.
 */
class TelephonyStore(private val context: Context) {

    data class ProviderSms(
        val id: Long,
        val address: String,
        val body: String,
        val date: Long,
        val incoming: Boolean,
        val read: Boolean,
        val subscriptionId: Int?,
    )

    private val resolver get() = context.contentResolver
    private val nameCache = ConcurrentHashMap<String, String>()

    private fun canRead() = context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    private fun canWrite() = DefaultSmsApp.isDefault(context)

    fun insertIncoming(address: String, body: String, date: Long, subscriptionId: Int?): Long? {
        if (!canWrite()) return null
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.DATE_SENT, date)
            put(Telephony.Sms.READ, 0)
            put(Telephony.Sms.SEEN, 0)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
            subscriptionId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
        }
        return insert(Telephony.Sms.Inbox.CONTENT_URI, values)
    }

    fun insertOutgoing(address: String, body: String, date: Long, subscriptionId: Int?): Long? {
        if (!canWrite()) return null
        val values = ContentValues().apply {
            put(Telephony.Sms.ADDRESS, address)
            put(Telephony.Sms.BODY, body)
            put(Telephony.Sms.DATE, date)
            put(Telephony.Sms.READ, 1)
            put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_OUTBOX)
            subscriptionId?.let { put(Telephony.Sms.SUBSCRIPTION_ID, it) }
        }
        return insert(Telephony.Sms.Outbox.CONTENT_URI, values)
    }

    fun markSent(providerId: Long, success: Boolean) {
        if (!canWrite()) return
        val type = if (success) Telephony.Sms.MESSAGE_TYPE_SENT else Telephony.Sms.MESSAGE_TYPE_FAILED
        runCatching {
            resolver.update(
                Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, providerId.toString()),
                ContentValues().apply { put(Telephony.Sms.TYPE, type) },
                null,
                null,
            )
        }
    }

    fun markRead(providerIds: List<Long>) {
        if (!canWrite() || providerIds.isEmpty()) return
        runCatching {
            providerIds.chunked(200).forEach { chunk ->
                resolver.update(
                    Telephony.Sms.CONTENT_URI,
                    ContentValues().apply {
                        put(Telephony.Sms.READ, 1)
                        put(Telephony.Sms.SEEN, 1)
                    },
                    "${Telephony.Sms._ID} IN (${chunk.joinToString(",") { "?" }})",
                    chunk.map(Long::toString).toTypedArray(),
                )
            }
        }
    }

    fun delete(providerId: Long) {
        if (!canWrite()) return
        runCatching { resolver.delete(Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, providerId.toString()), null, null) }
    }

    // ---- MMS mirror (so other SMS apps / backups see the same history) -------------------

    /** One part to mirror: text inline, or a file streamed into the provider. */
    data class MmsMirrorPart(val mimeType: String, val text: String? = null, val file: java.io.File? = null)

    /**
     * Writes an MMS into the system store (content://mms). [incoming]: [from] is the sender; else
     * [to] are the recipients and the MMSC fills in our own number. Returns the provider id.
     * Best effort: any provider error leaves our encrypted copy untouched.
     */
    fun insertMms(
        incoming: Boolean,
        from: String?,
        to: List<String>,
        subject: String?,
        date: Long,
        read: Boolean,
        subscriptionId: Int?,
        parts: List<MmsMirrorPart>,
    ): Long? {
        if (!canWrite()) return null
        return try {
            val addresses = (listOfNotNull(from) + to).filter { it.isNotBlank() }.toSet()
            if (addresses.isEmpty()) return null
            val threadId = Telephony.Threads.getOrCreateThreadId(context, addresses)
            val values = ContentValues().apply {
                put(Telephony.Mms.THREAD_ID, threadId)
                put(Telephony.Mms.DATE, date / 1000)
                put(Telephony.Mms.DATE_SENT, date / 1000)
                put(Telephony.Mms.READ, if (read) 1 else 0)
                put(Telephony.Mms.SEEN, if (read) 1 else 0)
                put(Telephony.Mms.MESSAGE_BOX, if (incoming) Telephony.Mms.MESSAGE_BOX_INBOX else Telephony.Mms.MESSAGE_BOX_OUTBOX)
                put(Telephony.Mms.MESSAGE_TYPE, if (incoming) MMS_TYPE_RETRIEVE_CONF else MMS_TYPE_SEND_REQ)
                put(Telephony.Mms.MMS_VERSION, MMS_VERSION_1_2)
                put(Telephony.Mms.CONTENT_TYPE, "application/vnd.wap.multipart.related")
                put(Telephony.Mms.TEXT_ONLY, if (parts.all { it.file == null }) 1 else 0)
                subject?.let { put(Telephony.Mms.SUBJECT, it); put(Telephony.Mms.SUBJECT_CHARSET, UTF8) }
                subscriptionId?.let { put(Telephony.Mms.SUBSCRIPTION_ID, it) }
            }
            val id = resolver.insert(Telephony.Mms.CONTENT_URI, values)?.lastPathSegment?.toLongOrNull() ?: return null
            val partUri = Uri.parse("content://mms/$id/part")
            parts.forEachIndexed { i, p ->
                val name = if (p.text != null) "text_$i.txt" else "part_$i.${p.file?.extension ?: "bin"}"
                val pv = ContentValues().apply {
                    put(Telephony.Mms.Part.MSG_ID, id)
                    put(Telephony.Mms.Part.CONTENT_TYPE, p.mimeType)
                    put(Telephony.Mms.Part.CONTENT_ID, "<$name>")
                    put(Telephony.Mms.Part.CONTENT_LOCATION, name)
                    if (p.text != null) {
                        put(Telephony.Mms.Part.CHARSET, UTF8)
                        put(Telephony.Mms.Part.TEXT, p.text)
                    }
                }
                val uri = resolver.insert(partUri, pv) ?: return@forEachIndexed
                p.file?.let { f -> resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } } }
            }
            val addrUri = Uri.parse("content://mms/$id/addr")
            fun addr(a: String, type: Int) = resolver.insert(
                addrUri,
                ContentValues().apply {
                    put(Telephony.Mms.Addr.MSG_ID, id)
                    put(Telephony.Mms.Addr.ADDRESS, a)
                    put(Telephony.Mms.Addr.TYPE, type)
                    put(Telephony.Mms.Addr.CHARSET, UTF8)
                },
            )
            addr(if (incoming) from ?: INSERT_ADDRESS_TOKEN else INSERT_ADDRESS_TOKEN, PDU_FROM)
            to.forEach { addr(it, PDU_TO) }
            id
        } catch (e: Exception) {
            SafeLog.e(TAG, "mms provider insert failed", e)
            null
        }
    }

    fun markMmsSent(providerId: Long, success: Boolean) {
        if (!canWrite()) return
        val box = if (success) Telephony.Mms.MESSAGE_BOX_SENT else Telephony.Mms.MESSAGE_BOX_FAILED
        runCatching {
            resolver.update(
                Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, providerId.toString()),
                ContentValues().apply { put(Telephony.Mms.MESSAGE_BOX, box) }, null, null,
            )
        }
    }

    fun markMmsRead(providerIds: List<Long>) {
        if (!canWrite() || providerIds.isEmpty()) return
        runCatching {
            providerIds.chunked(200).forEach { chunk ->
                resolver.update(
                    Telephony.Mms.CONTENT_URI,
                    ContentValues().apply { put(Telephony.Mms.READ, 1); put(Telephony.Mms.SEEN, 1) },
                    "${Telephony.Mms._ID} IN (${chunk.joinToString(",") { "?" }})",
                    chunk.map(Long::toString).toTypedArray(),
                )
            }
        }
    }

    fun deleteMms(providerId: Long) {
        if (!canWrite()) return
        runCatching { resolver.delete(Uri.withAppendedPath(Telephony.Mms.CONTENT_URI, providerId.toString()), null, null) }
    }

    /** Reads SMS rows with _id > [afterId], oldest first, at most [limit]. */
    fun readSince(afterId: Long, limit: Int): List<ProviderSms> {
        if (!canRead()) return emptyList()
        val out = ArrayList<ProviderSms>(limit)
        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE,
            Telephony.Sms.TYPE, Telephony.Sms.READ, Telephony.Sms.SUBSCRIPTION_ID,
        )
        try {
            resolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                "${Telephony.Sms._ID} > ? AND ${Telephony.Sms.TYPE} IN (?, ?)",
                arrayOf(afterId.toString(), Telephony.Sms.MESSAGE_TYPE_INBOX.toString(), Telephony.Sms.MESSAGE_TYPE_SENT.toString()),
                "${Telephony.Sms._ID} ASC LIMIT $limit",
            )?.use { c ->
                val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
                val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val iType = c.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                val iRead = c.getColumnIndexOrThrow(Telephony.Sms.READ)
                val iSub = c.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
                while (c.moveToNext()) {
                    val address = c.getString(iAddr) ?: continue
                    out += ProviderSms(
                        id = c.getLong(iId),
                        address = address,
                        body = c.getString(iBody).orEmpty(),
                        date = c.getLong(iDate),
                        incoming = c.getInt(iType) == Telephony.Sms.MESSAGE_TYPE_INBOX,
                        read = c.getInt(iRead) == 1,
                        subscriptionId = if (iSub >= 0 && !c.isNull(iSub)) c.getInt(iSub) else null,
                    )
                }
            }
        } catch (e: SecurityException) {
            SafeLog.w(TAG, "sms provider read denied")
        }
        return out
    }

    /** Contact display name for a phone number, or null. Read-only, cached in memory. */
    fun contactName(address: String): String? {
        if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        if (address.count(Char::isDigit) < 5) return null
        nameCache[address]?.let { return it.ifEmpty { null } }
        val name = runCatching {
            resolver.query(
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()
        nameCache[address] = name.orEmpty()
        return name
    }

    private fun insert(uri: Uri, values: ContentValues): Long? =
        try {
            resolver.insert(uri, values)?.lastPathSegment?.toLongOrNull()
        } catch (e: Exception) {
            SafeLog.e(TAG, "sms provider insert failed", e)
            null
        }

    private companion object {
        const val TAG = "TelephonyStore"
        const val UTF8 = 106
        const val MMS_TYPE_SEND_REQ = 128
        const val MMS_TYPE_RETRIEVE_CONF = 132
        const val MMS_VERSION_1_2 = 0x12
        const val PDU_FROM = 137
        const val PDU_TO = 151
        const val INSERT_ADDRESS_TOKEN = "insert-address-token"
    }
}
