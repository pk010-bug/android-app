package app.privateinbox.attachments

/**
 * Received-MMS media rules (pure, unit-tested). The sender's declared content type is NOT
 * trusted: bytes must match a known signature for an allow-listed type, and size is capped.
 * Non-image media is never rendered or auto-opened by us; the user may explicitly hand a
 * private copy to another app through our non-exported FileProvider.
 */
object MediaPolicy {
    enum class Kind { IMAGE, VIDEO, AUDIO, VCARD }

    data class MediaType(val kind: Kind, val mime: String, val extension: String)

    const val MAX_BYTES: Long = 5L * 1024 * 1024
    const val HEAD_BYTES = 64

    sealed interface Decision {
        data class Accept(val type: MediaType) : Decision
        data class Reject(val reason: String) : Decision
    }

    fun check(data: ByteArray): Decision {
        if (data.isEmpty()) return Decision.Reject("empty")
        if (data.size > MAX_BYTES) return Decision.Reject("too large")
        val type = sniff(data.copyOf(minOf(HEAD_BYTES, data.size))) ?: return Decision.Reject("unsupported type")
        return Decision.Accept(type)
    }

    fun sniff(head: ByteArray): MediaType? {
        AttachmentPolicy.sniff(head)?.let { return MediaType(Kind.IMAGE, it.mime, it.extension) }
        fun at(i: Int) = if (i < head.size) head[i].toInt() and 0xFF else -1
        fun ascii(offset: Int, s: String) = s.indices.all { at(offset + it) == s[it].code }
        if (ascii(4, "ftyp")) {
            val brand = (8 until 12).map { at(it).toChar() }.joinToString("")
            return when {
                brand.startsWith("3gp") || brand.startsWith("3g2") -> MediaType(Kind.VIDEO, "video/3gpp", "3gp")
                brand == "M4A " -> MediaType(Kind.AUDIO, "audio/mp4", "m4a")
                brand in setOf("isom", "iso2", "mp41", "mp42", "avc1", "M4V ", "MSNV", "dash") ->
                    MediaType(Kind.VIDEO, "video/mp4", "mp4")
                else -> null
            }
        }
        return when {
            ascii(0, "#!AMR-WB\n") -> MediaType(Kind.AUDIO, "audio/amr-wb", "awb")
            ascii(0, "#!AMR\n") -> MediaType(Kind.AUDIO, "audio/amr", "amr")
            ascii(0, "OggS") -> MediaType(Kind.AUDIO, "audio/ogg", "ogg")
            ascii(0, "ID3") || (at(0) == 0xFF && at(1) in setOf(0xFB, 0xF3, 0xF2)) -> MediaType(Kind.AUDIO, "audio/mpeg", "mp3")
            at(0) == 0xFF && (at(1) == 0xF1 || at(1) == 0xF9) -> MediaType(Kind.AUDIO, "audio/aac", "aac")
            ascii(0, "RIFF") && ascii(8, "WAVE") -> MediaType(Kind.AUDIO, "audio/wav", "wav")
            isVcard(head) -> MediaType(Kind.VCARD, "text/x-vcard", "vcf")
            else -> null
        }
    }

    private fun isVcard(head: ByteArray): Boolean {
        var s = String(head, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        return s.startsWith("BEGIN:VCARD", ignoreCase = true)
    }

    /** Extension → (kind, mime) for files we stored ourselves (names are validated elsewhere). */
    fun forExtension(ext: String): MediaType? = ALL.firstOrNull { it.extension == ext }

    private val ALL = ImageType.entries.map { MediaType(Kind.IMAGE, it.mime, it.extension) } + listOf(
        MediaType(Kind.VIDEO, "video/3gpp", "3gp"), MediaType(Kind.VIDEO, "video/mp4", "mp4"),
        MediaType(Kind.AUDIO, "audio/mp4", "m4a"), MediaType(Kind.AUDIO, "audio/amr-wb", "awb"),
        MediaType(Kind.AUDIO, "audio/amr", "amr"), MediaType(Kind.AUDIO, "audio/ogg", "ogg"),
        MediaType(Kind.AUDIO, "audio/mpeg", "mp3"), MediaType(Kind.AUDIO, "audio/aac", "aac"),
        MediaType(Kind.AUDIO, "audio/wav", "wav"), MediaType(Kind.VCARD, "text/x-vcard", "vcf"),
    )
}
