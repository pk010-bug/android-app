package app.privateinbox.attachments

/** Outcome of checking an attachment URI handed to us by another app. */
sealed interface UriDecision {
    data object Accept : UriDecision
    data class Reject(val reason: Reason) : UriDecision

    enum class Reason {
        NOT_CONTENT_SCHEME, OWN_PROVIDER, MALFORMED, MISSING_AUTHORITY,
        /** Telephony / contacts / other personal-data providers: never imported from a share. */
        BLOCKED_PROVIDER,
        /** We could read it only through our own permissions (confused deputy), not the caller's grant. */
        PRIVILEGED_PROVIDER,
        /** Protected provider and the sending app did not grant us read access to this URI. */
        NOT_GRANTED,
    }
}

enum class ImageType(val mime: String, val extension: String) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    GIF("image/gif", "gif"),
    WEBP("image/webp", "webp"),
    HEIC("image/heic", "heic"),
}

sealed interface ContentDecision {
    data class Accept(val type: ImageType) : ContentDecision
    data class Reject(val reason: String) : ContentDecision
}

/**
 * Attachment rules (pure, unit-tested):
 *  - Only `content://` URIs from OTHER apps. `file://` is rejected (path-traversal / reading our
 *    own private files via a confused deputy). URIs pointing at our own FileProvider or any
 *    authority under our package are rejected too, so a malicious app cannot make us "attach"
 *    our own private data and leak it.
 *  - Bytes must start with a known image signature; the declared MIME type is not trusted.
 *  - Personal-data providers (SMS/MMS, contacts, call log, ...) are blocked outright, and a URI on
 *    any protected provider is accepted only when the sender granted it to us AND our own
 *    permissions would not let us read it anyway ([checkSource]); otherwise another app could
 *    make us attach data it cannot read itself (confused deputy).
 *  - Hard size caps; shared URIs are only copied when the user taps "Attach", and only the
 *    private copy is used afterwards.
 */
object AttachmentPolicy {

    const val MAX_BYTES: Long = 5L * 1024 * 1024 // 5 MiB before (future) MMS compression

    /** The authority prefix that marks "ours"; anything under it is rejected. */
    const val OWN_AUTHORITY_PREFIX = "app.privateinbox"

    /** Total size the private attachments folder may hold before oldest copies are evicted. */
    const val MAX_STORE_BYTES: Long = 20L * 1024 * 1024

    /** Authorities holding personal data that a share must never pull in (incl. user-prefixed forms). */
    val BLOCKED_AUTHORITIES = setOf(
        "sms", "mms", "mms-sms", "telephony", "icc", "call_log", "call_log_shadow",
        "contacts", "com.android.contacts", "com.android.blockednumber", "com.android.voicemail",
        "user_dictionary", "settings", "com.android.calendar", "downloads", "com.android.providers.downloads",
        "com.android.providers.telephony", "com.android.mms.file",
    )

    /** What [checkSource] needs to know about the provider behind a URI (from PackageManager). */
    data class ProviderFacts(
        val exported: Boolean,
        val readPermission: String?,
        val pathReadPermissions: List<String> = emptyList(),
    )

    /**
     * Confused-deputy check for a URI another app handed us.
     *  - Public provider (exported, no read permission anywhere): anyone can read it -> accept.
     *  - Protected provider whose permission WE hold (e.g. READ_SMS, READ_CONTACTS): reject, a
     *    grant proves nothing because the system skips the caller check when the target already
     *    has access.
     *  - Otherwise accept only a URI the sender explicitly granted (listed in the intent's ClipData
     *    with FLAG_GRANT_READ_URI_PERMISSION, which the system validates against the sender).
     *  - [provider] null = unresolvable/invisible: only a caller grant can make it readable.
     */
    fun checkSource(provider: ProviderFacts?, weHold: (String) -> Boolean, grantedByCaller: Boolean): UriDecision {
        if (provider != null) {
            val perms = listOfNotNull(provider.readPermission) + provider.pathReadPermissions
            if (provider.exported && perms.isEmpty()) return UriDecision.Accept
            if (perms.any(weHold)) return UriDecision.Reject(UriDecision.Reason.PRIVILEGED_PROVIDER)
        }
        return if (grantedByCaller) UriDecision.Accept else UriDecision.Reject(UriDecision.Reason.NOT_GRANTED)
    }

    fun checkUri(uri: String, ownAuthorities: Set<String>): UriDecision {
        val trimmed = uri.trim()
        val scheme = trimmed.substringBefore(':', "").lowercase()
        if (scheme.isEmpty()) return UriDecision.Reject(UriDecision.Reason.MALFORMED)
        if (scheme != "content") return UriDecision.Reject(UriDecision.Reason.NOT_CONTENT_SCHEME)
        if (!trimmed.substring(scheme.length).startsWith("://")) {
            return UriDecision.Reject(UriDecision.Reason.MALFORMED)
        }
        val rest = trimmed.substring("content://".length)
        // Strip any userinfo ("user@host") and port, Android authorities never have them legitimately.
        val authorityRaw = rest.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authorityRaw.contains('@') || authorityRaw.contains('\\')) {
            return UriDecision.Reject(UriDecision.Reason.MALFORMED)
        }
        val authority = authorityRaw.lowercase()
        if (authority.isEmpty()) return UriDecision.Reject(UriDecision.Reason.MISSING_AUTHORITY)
        val own = ownAuthorities.map { it.lowercase() }
        if (authority in own ||
            authority == OWN_AUTHORITY_PREFIX ||
            authority.startsWith("$OWN_AUTHORITY_PREFIX.")
        ) {
            return UriDecision.Reject(UriDecision.Reason.OWN_PROVIDER)
        }
        if (authority.substringBefore(':') in BLOCKED_AUTHORITIES) return UriDecision.Reject(UriDecision.Reason.BLOCKED_PROVIDER)
        return UriDecision.Accept
    }

    /** Identify an image from its first bytes (needs at least 12). */
    fun sniff(head: ByteArray): ImageType? {
        fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
        fun ascii(offset: Int, s: String) = s.indices.all { at(offset + it) == s[it].code }
        return when {
            at(0) == 0xFF && at(1) == 0xD8 && at(2) == 0xFF -> ImageType.JPEG
            at(0) == 0x89 && ascii(1, "PNG") && at(4) == 0x0D && at(5) == 0x0A && at(6) == 0x1A && at(7) == 0x0A ->
                ImageType.PNG
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> ImageType.GIF
            ascii(0, "RIFF") && ascii(8, "WEBP") -> ImageType.WEBP
            ascii(4, "ftyp") && (ascii(8, "heic") || ascii(8, "heix") || ascii(8, "mif1") || ascii(8, "heif")) ->
                ImageType.HEIC
            else -> null
        }
    }

    /** Full content check: size (when known) then magic bytes. */
    fun checkContent(head: ByteArray, totalBytes: Long): ContentDecision {
        if (totalBytes <= 0) return ContentDecision.Reject("empty")
        if (totalBytes > MAX_BYTES) return ContentDecision.Reject("too large")
        val type = sniff(head) ?: return ContentDecision.Reject("not a supported image")
        return ContentDecision.Accept(type)
    }
}
