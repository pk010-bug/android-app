package app.privateinbox.mms

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/**
 * Minimal, defensive MMS (OMA MMS-ENC / WAP-230 WSP) PDU codec. Pure Kotlin, unit-tested.
 *
 * Supports exactly what PrivateInbox needs:
 *  - parse M-Notification.ind (from WAP push) and M-Retrieve.conf (downloaded message)
 *  - encode M-Send.req (text + image parts) and M-NotifyResp.ind (download acknowledgement)
 *
 * All reads are bounds-checked; malformed input throws [MalformedPdu] rather than crashing or
 * over-reading. Part counts and sizes are capped.
 */
object MmsPdu {

    class MalformedPdu(msg: String) : Exception(msg)

    // Message types (X-Mms-Message-Type values)
    const val TYPE_SEND_REQ = 0x80
    const val TYPE_NOTIFICATION_IND = 0x82
    const val TYPE_NOTIFYRESP_IND = 0x83
    const val TYPE_RETRIEVE_CONF = 0x84

    // Header field codes (already OR'ed with 0x80)
    const val H_BCC = 0x81
    const val H_CC = 0x82
    const val H_CONTENT_LOCATION = 0x83
    const val H_CONTENT_TYPE = 0x84
    const val H_DATE = 0x85
    const val H_FROM = 0x89
    const val H_MESSAGE_ID = 0x8B
    const val H_MESSAGE_TYPE = 0x8C
    const val H_MMS_VERSION = 0x8D
    const val H_MESSAGE_SIZE = 0x8E
    const val H_STATUS = 0x95
    const val H_SUBJECT = 0x96
    const val H_TO = 0x97
    const val H_TRANSACTION_ID = 0x98
    const val H_REPORT_ALLOWED = 0x91

    private const val MAX_PARTS = 40
    private const val MMS_VERSION_1_2 = 0x92

    private val WELL_KNOWN_TYPES = mapOf(
        0x00 to "*/*", 0x01 to "text/*", 0x02 to "text/html", 0x03 to "text/plain",
        0x06 to "text/x-vcalendar", 0x07 to "text/x-vcard", 0x0C to "multipart/mixed",
        0x0F to "multipart/alternative", 0x1C to "image/*", 0x1D to "image/gif", 0x1E to "image/jpeg",
        0x1F to "image/tiff", 0x20 to "image/png", 0x21 to "image/vnd.wap.wbmp",
        0x23 to "application/vnd.wap.multipart.mixed", 0x26 to "application/vnd.wap.multipart.alternative",
        0x33 to "application/vnd.wap.multipart.related",
    )
    private val TYPE_CODES = WELL_KNOWN_TYPES.entries.associate { (k, v) -> v to k }

    data class Part(val contentType: String, val name: String?, val charset: Int?, val data: ByteArray) {
        val isText get() = contentType.startsWith("text/plain")
        fun text(): String = String(data, charsetFor(charset))
        override fun equals(other: Any?) = other is Part && contentType == other.contentType &&
            name == other.name && charset == other.charset && data.contentEquals(other.data)
        override fun hashCode() = contentType.hashCode() * 31 + data.contentHashCode()
    }

    data class Pdu(
        val messageType: Int,
        val transactionId: String? = null,
        val from: String? = null,
        val to: List<String> = emptyList(),
        val cc: List<String> = emptyList(),
        val subject: String? = null,
        val dateSeconds: Long? = null,
        val contentLocation: String? = null,
        val messageSize: Long? = null,
        val contentType: String? = null,
        val parts: List<Part> = emptyList(),
    )

    // ------------------------------------------------------------------ parsing

    /**
     * Parses an untrusted PDU. Any failure (including an unexpected runtime error or an allocation
     * failure from a hostile length) is reported as [MalformedPdu]; nothing else escapes.
     */
    fun parse(bytes: ByteArray): Pdu = try {
        parseUnchecked(bytes)
    } catch (e: MalformedPdu) {
        throw e
    } catch (e: RuntimeException) {
        throw MalformedPdu("internal: ${e.javaClass.simpleName}")
    } catch (e: OutOfMemoryError) {
        throw MalformedPdu("allocation refused")
    }

    /** Test hook: same parser without the safety net, so fuzz tests see real bugs. */
    internal fun parseUnchecked(bytes: ByteArray): Pdu {
        val r = Reader(bytes)
        var type = -1
        var tid: String? = null
        var from: String? = null
        val to = mutableListOf<String>()
        val cc = mutableListOf<String>()
        var subject: String? = null
        var date: Long? = null
        var location: String? = null
        var size: Long? = null
        var contentType: String? = null
        var parts: List<Part> = emptyList()

        while (r.hasMore()) {
            val field = r.u8()
            if (field and 0x80 == 0) throw MalformedPdu("unexpected non-header byte")
            when (field) {
                H_MESSAGE_TYPE -> type = r.u8()
                H_TRANSACTION_ID -> tid = r.textString()
                H_FROM -> from = r.fromValue()
                H_TO -> to += cleanAddress(r.encodedString())
                H_CC -> cc += cleanAddress(r.encodedString())
                H_SUBJECT -> subject = r.encodedString()
                H_DATE -> date = r.longInteger()
                H_CONTENT_LOCATION -> location = r.textString()
                H_MESSAGE_SIZE -> size = r.longInteger()
                H_CONTENT_TYPE -> {
                    contentType = r.contentType().first
                    // Content-Type is always the last header; the body follows.
                    if (contentType.contains("multipart")) parts = r.multipart()
                    break
                }
                else -> r.skipValue()
            }
        }
        if (type < 0) throw MalformedPdu("missing message type")
        return Pdu(type, tid, from?.let(::cleanAddress), to, cc, subject, date, location, size, contentType, parts)
    }

    fun cleanAddress(raw: String): String = raw.substringBefore("/TYPE=").trim()

    private class Reader(private val b: ByteArray, var pos: Int = 0, private val end: Int = b.size) {
        fun hasMore() = pos < end
        fun u8(): Int {
            if (pos >= end) throw MalformedPdu("truncated")
            return b[pos++].toInt() and 0xFF
        }
        fun peek(): Int = if (pos < end) b[pos].toInt() and 0xFF else throw MalformedPdu("truncated")
        /** Overflow-safe: returns pos + n only when 0 <= n <= remaining bytes. */
        fun stopAfter(n: Long): Int {
            if (n < 0 || n > (end - pos).toLong()) throw MalformedPdu("length out of range")
            return pos + n.toInt()
        }
        fun stopAfter(n: Int): Int = stopAfter(n.toLong())
        fun bytes(n: Long): ByteArray {
            val stop = stopAfter(n)
            return b.copyOfRange(pos, stop).also { pos = stop }
        }
        fun bytes(n: Int): ByteArray = bytes(n.toLong())
        /** Jumps to [stop], refusing to go past it if a nested read overran the declared length. */
        fun seek(stop: Int) {
            if (pos > stop || stop > end) throw MalformedPdu("value overran its length")
            pos = stop
        }
        fun uintvar(): Long {
            var v = 0L
            repeat(5) {
                val x = u8()
                v = (v shl 7) or (x and 0x7F).toLong()
                if (x and 0x80 == 0) return v
            }
            throw MalformedPdu("uintvar too long")
        }
        fun valueLength(): Int {
            val x = u8()
            return when {
                x <= 30 -> x
                x == 31 -> uintvar().let { if (it > (end - pos).toLong()) throw MalformedPdu("length") else it.toInt() }
                else -> throw MalformedPdu("not a value-length")
            }
        }
        fun textString(): String {
            if (peek() == 0x7F || peek() == 0x22) pos++
            val start = pos
            while (pos < end && b[pos].toInt() != 0) pos++
            if (pos >= end) throw MalformedPdu("unterminated string")
            return String(b, start, pos - start, Charsets.UTF_8).also { pos++ }
        }
        fun longInteger(): Long {
            val first = peek()
            if (first and 0x80 != 0) return (u8() and 0x7F).toLong()
            val n = u8()
            if (n !in 1..8) throw MalformedPdu("long-integer size")
            var v = 0L
            repeat(n) { v = (v shl 8) or u8().toLong() }
            return v
        }
        fun integerValue(): Long = longInteger()
        fun encodedString(): String {
            val first = peek()
            if (first > 31) return textString()
            val stop = stopAfter(valueLength())
            val charset = integerValue().toInt()
            if (peek() == 0x7F) pos++
            if (pos > stop) throw MalformedPdu("encoded string overran")
            val s = pos
            var e = s
            while (e < stop && b[e].toInt() != 0) e++
            val text = String(b, s, e - s, charsetFor(charset))
            seek(stop)
            return text
        }
        fun fromValue(): String? {
            val stop = stopAfter(valueLength())
            val token = u8()
            val v = if (token == 0x80) encodedString() else null // 0x81 = insert-address
            seek(stop)
            return v
        }
        /** Skips any WSP-encoded value. */
        fun skipValue() {
            val x = peek()
            when {
                x <= 30 -> { pos++; bytes(x) }
                x == 31 -> { pos++; bytes(uintvar()) }
                x in 32..127 -> textString()
                else -> pos++
            }
        }
        /** Returns (mediaType, params). */
        fun contentType(): Pair<String, Map<Int, Any>> {
            val x = peek()
            if (x >= 0x80) { pos++; return (WELL_KNOWN_TYPES[x and 0x7F] ?: "application/octet-stream") to emptyMap() }
            if (x in 32..127) return textString() to emptyMap()
            val stop = stopAfter(valueLength())
            val media = when {
                peek() >= 0x80 -> WELL_KNOWN_TYPES[u8() and 0x7F] ?: "application/octet-stream"
                peek() in 1..30 -> { longInteger(); "application/octet-stream" }
                else -> textString()
            }
            val params = HashMap<Int, Any>()
            while (pos < stop) {
                val t = peek()
                if (t >= 0x80) {
                    pos++
                    when (t) {
                        0x81 -> params[0x81] = integerValue().toInt() // charset
                        0x85, 0x97 -> params[0x85] = textString() // name
                        0x89, 0x8A -> params[t] = if (peek() in 32..127) textString() else { skipValue(); "" }
                        else -> skipValue()
                    }
                } else {
                    textString() // untyped parameter name
                    if (pos < stop) skipValue()
                }
            }
            seek(stop)
            return media to params
        }
        fun multipart(): List<Part> {
            val count = uintvar()
            if (count > MAX_PARTS) throw MalformedPdu("too many parts")
            val out = ArrayList<Part>()
            repeat(count.toInt()) {
                val headersLen = uintvar()
                val dataLen = uintvar()
                val headerEnd = stopAfter(headersLen)
                if (dataLen > (end - headerEnd).toLong()) throw MalformedPdu("part data length")
                val (type, params) = contentType()
                if (pos > headerEnd) throw MalformedPdu("part content-type overran headers")
                var name = params[0x85] as? String
                while (pos < headerEnd) {
                    val h = u8()
                    if (h == 0x8E && name == null) name = textString() // Content-Location
                    else if (h >= 0x80) skipValue()
                    else { pos--; textString(); if (pos < headerEnd) skipValue() } // application header
                }
                seek(headerEnd)
                out += Part(type, name, params[0x81] as? Int, bytes(dataLen))
            }
            return out
        }
    }

    fun charsetFor(mib: Int?): Charset = when (mib) {
        3 -> Charsets.US_ASCII
        4 -> Charsets.ISO_8859_1
        1000 -> Charsets.UTF_16BE
        1013 -> Charsets.UTF_16BE
        1015 -> Charsets.UTF_16
        else -> Charsets.UTF_8
    }

    // ------------------------------------------------------------------ encoding

    private class Writer {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun bytes(b: ByteArray) = out.write(b)
        fun text(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            if (b.isNotEmpty() && (b[0].toInt() and 0xFF) >= 0x80) u8(0x7F)
            bytes(b)
            u8(0)
        }
        fun uintvar(v: Long) {
            val tmp = ArrayList<Int>()
            var x = v
            tmp += (x and 0x7F).toInt()
            x = x shr 7
            while (x > 0) { tmp += ((x and 0x7F) or 0x80).toInt(); x = x shr 7 }
            tmp.reversed().forEach(::u8)
        }
        fun valueLength(n: Int) = if (n <= 30) u8(n) else { u8(31); uintvar(n.toLong()) }
        fun toBytes(): ByteArray = out.toByteArray()
    }

    /** utf-8 encoded-string-value (charset 106). */
    private fun Writer.encodedUtf8(s: String) {
        val inner = Writer().apply { u8(0xEA); text(s) }.toBytes()
        valueLength(inner.size)
        bytes(inner)
    }

    data class OutPart(val contentType: String, val name: String, val data: ByteArray)

    fun encodeSendReq(transactionId: String, to: List<String>, subject: String?, parts: List<OutPart>): ByteArray {
        require(to.isNotEmpty()) { "no recipients" }
        require(parts.size in 1..MAX_PARTS) { "bad part count" }
        val w = Writer()
        w.u8(H_MESSAGE_TYPE); w.u8(TYPE_SEND_REQ)
        w.u8(H_TRANSACTION_ID); w.text(transactionId)
        w.u8(H_MMS_VERSION); w.u8(MMS_VERSION_1_2)
        w.u8(H_FROM); w.u8(1); w.u8(0x81) // insert-address: the MMSC fills in our number
        to.forEach { addr ->
            val clean = addr.filter { it.isDigit() || it == '+' }
            require(clean.length in 3..20) { "bad recipient" }
            w.u8(H_TO); w.text("$clean/TYPE=PLMN")
        }
        subject?.takeIf { it.isNotBlank() }?.let { w.u8(H_SUBJECT); w.encodedUtf8(it.take(40)) }
        w.u8(H_CONTENT_TYPE); w.u8(0x80 or TYPE_CODES.getValue("application/vnd.wap.multipart.mixed"))
        w.uintvar(parts.size.toLong())
        parts.forEach { p ->
            val ct = Writer().apply {
                val inner = Writer().apply {
                    val code = TYPE_CODES[p.contentType]
                    if (code != null) u8(0x80 or code) else text(p.contentType)
                    if (p.contentType.startsWith("text/")) { u8(0x81); u8(0xEA) } // charset=utf-8
                    u8(0x85); text(p.name) // name
                }.toBytes()
                valueLength(inner.size)
                bytes(inner)
                u8(0x8E); text(p.name) // Content-Location
            }.toBytes()
            w.uintvar(ct.size.toLong())
            w.uintvar(p.data.size.toLong())
            w.bytes(ct)
            w.bytes(p.data)
        }
        return w.toBytes()
    }

    fun encodeNotifyResp(transactionId: String): ByteArray = Writer().apply {
        u8(H_MESSAGE_TYPE); u8(TYPE_NOTIFYRESP_IND)
        u8(H_TRANSACTION_ID); text(transactionId)
        u8(H_MMS_VERSION); u8(MMS_VERSION_1_2)
        u8(H_STATUS); u8(0x81) // retrieved
        u8(H_REPORT_ALLOWED); u8(0x81) // no: don't send read/delivery reports about the user
    }.toBytes()
}
