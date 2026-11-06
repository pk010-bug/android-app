package app.privateinbox.attachments

import app.privateinbox.attachments.MediaPolicy.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPolicyTest {
    private fun b(s: String) = s.toByteArray(Charsets.ISO_8859_1)
    private fun ftyp(brand: String) = byteArrayOf(0, 0, 0, 24) + b("ftyp$brand") + ByteArray(16)

    @Test fun sniffsAllowedTypes() {
        assertEquals(Kind.VIDEO to "video/mp4", MediaPolicy.sniff(ftyp("isom"))!!.let { it.kind to it.mime })
        assertEquals("video/3gpp", MediaPolicy.sniff(ftyp("3gp5"))!!.mime)
        assertEquals("audio/mp4", MediaPolicy.sniff(ftyp("M4A "))!!.mime)
        assertEquals(Kind.IMAGE, MediaPolicy.sniff(ftyp("heic"))!!.kind)
        assertEquals("audio/amr", MediaPolicy.sniff(b("#!AMR\n....."))!!.mime)
        assertEquals("audio/amr-wb", MediaPolicy.sniff(b("#!AMR-WB\n...."))!!.mime)
        assertEquals("audio/ogg", MediaPolicy.sniff(b("OggS\u0000\u0002"))!!.mime)
        assertEquals("audio/mpeg", MediaPolicy.sniff(b("ID3\u0004"))!!.mime)
        assertEquals("audio/wav", MediaPolicy.sniff(b("RIFF\u0000\u0000\u0000\u0000WAVEfmt "))!!.mime)
        assertEquals(Kind.VCARD, MediaPolicy.sniff(b("\uFEFF\r\nbegin:vcard\r\nVERSION:3.0"))?.kind ?: MediaPolicy.sniff("\r\nbegin:vcard\r\n".toByteArray())!!.kind)
        assertEquals(Kind.IMAGE, MediaPolicy.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0))!!.kind)
    }

    @Test fun rejectsEverythingElse() {
        listOf(
            b("MZ\u0090\u0000"), // Windows exe
            b("PK\u0003\u0004"), // zip / apk
            b("\u007FELF"), // native binary
            b("<html><script>"),
            b("%PDF-1.7"),
            b("dex\n035\u0000"),
            ftyp("qt  "),
        ).forEach { assertNull(String(it, Charsets.ISO_8859_1), MediaPolicy.sniff(it)) }
    }

    @Test fun sizeAndEmptyChecks() {
        assertTrue(MediaPolicy.check(ByteArray(0)) is MediaPolicy.Decision.Reject)
        val huge = ByteArray((MediaPolicy.MAX_BYTES + 1).toInt()).also { b("OggS").copyInto(it) }
        assertTrue(MediaPolicy.check(huge) is MediaPolicy.Decision.Reject)
        assertTrue(MediaPolicy.check(b("OggSxxxx")) is MediaPolicy.Decision.Accept)
    }

    @Test fun extensionLookupRoundTrips() {
        listOf("jpg", "mp4", "3gp", "amr", "ogg", "vcf").forEach { assertEquals(it, MediaPolicy.forExtension(it)!!.extension) }
        assertNull(MediaPolicy.forExtension("apk"))
    }
}
