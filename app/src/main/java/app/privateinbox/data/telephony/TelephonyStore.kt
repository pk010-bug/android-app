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
    }
}
