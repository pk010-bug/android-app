package app.privateinbox.contacts

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone

/**
 * Read-only, on-device contact lookups for the composer. Nothing is cached or copied into our DB.
 * Picking via the system picker works without READ_CONTACTS (one-time URI grant); typeahead
 * suggestions need READ_CONTACTS and silently return nothing without it.
 */
class ContactLookup(private val context: Context) {
    data class Suggestion(val name: String, val number: String, val label: String?)

    fun canSuggest() = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun suggest(query: String, limit: Int = 6): List<Suggestion> {
        val q = query.trim()
        if (q.length < 2 || q.length > 40 || !canSuggest()) return emptyList()
        val uri = Uri.withAppendedPath(Phone.CONTENT_FILTER_URI, Uri.encode(q))
        return runCatching {
            context.contentResolver.query(
                uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER, Phone.TYPE, Phone.LABEL), null, null, null,
            )?.use { c ->
                val out = ArrayList<Suggestion>()
                while (c.moveToNext() && out.size < limit) {
                    val number = c.getString(1) ?: continue
                    val label = Phone.getTypeLabel(context.resources, c.getInt(2), c.getString(3)).toString()
                    out += Suggestion(c.getString(0) ?: number, number, label)
                }
                out.distinctBy { it.number.filter(Char::isDigit) }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    /** Resolves a phone data row returned by ACTION_PICK on [Phone.CONTENT_URI]. */
    fun resolvePicked(uri: Uri): Suggestion? {
        if (uri.scheme != "content" || uri.authority != android.provider.ContactsContract.AUTHORITY) return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null)?.use { c ->
                if (!c.moveToFirst()) null else c.getString(1)?.let { Suggestion(c.getString(0) ?: it, it, null) }
            }
        }.getOrNull()
    }
}
