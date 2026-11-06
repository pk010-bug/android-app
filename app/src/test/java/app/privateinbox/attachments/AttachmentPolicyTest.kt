package app.privateinbox.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentPolicyTest {
    private val own = setOf("app.privateinbox.fileprovider")
    private fun check(uri: String) = AttachmentPolicy.checkUri(uri, own)
    private fun reason(uri: String) = (check(uri) as UriDecision.Reject).reason

    @Test fun acceptsContentFromOtherApps() {
        assertEquals(UriDecision.Accept, check("content://media/external/images/media/42"))
        assertEquals(UriDecision.Accept, check("content://com.google.android.apps.photos.contentprovider/0/1/x"))
        assertEquals(UriDecision.Accept, check("CONTENT://com.example.provider/img.jpg"))
    }

    @Test fun rejectsFileAndOtherSchemes() {
        assertEquals(UriDecision.Reason.NOT_CONTENT_SCHEME, reason("file:///sdcard/DCIM/a.jpg"))
        assertEquals(UriDecision.Reason.NOT_CONTENT_SCHEME, reason("FILE:///data/data/app.privateinbox/x"))
        assertEquals(UriDecision.Reason.NOT_CONTENT_SCHEME, reason("http://example.com/a.png"))
        assertEquals(UriDecision.Reason.NOT_CONTENT_SCHEME, reason("android.resource://pkg/1"))
        assertEquals(UriDecision.Reason.MALFORMED, reason("/sdcard/a.jpg"))
    }

    @Test fun rejectsOwnProviderAndPackageAuthorities() {
        assertEquals(UriDecision.Reason.OWN_PROVIDER, reason("content://app.privateinbox.fileprovider/outbound_share/a.png"))
        assertEquals(UriDecision.Reason.OWN_PROVIDER, reason("content://APP.PRIVATEINBOX.FileProvider/a"))
        assertEquals(UriDecision.Reason.OWN_PROVIDER, reason("content://app.privateinbox.androidx-startup/x"))
    }

    @Test fun rejectsMalformedAuthorities() {
        assertEquals(UriDecision.Reason.MISSING_AUTHORITY, reason("content:///x"))
        assertEquals(UriDecision.Reason.MALFORMED, reason("content:media/1"))
        assertEquals(UriDecision.Reason.MALFORMED, reason("content://evil@app.privateinbox.fileprovider/x"))
    }

    private fun bytes(vararg v: Int) = ByteArray(16).also { b -> v.forEachIndexed { i, x -> b[i] = x.toByte() } }
    private fun ascii(s: String, pad: Int = 16) = s.toByteArray(Charsets.US_ASCII).copyOf(pad)

    @Test fun sniffsKnownImageSignatures() {
        assertEquals(ImageType.JPEG, AttachmentPolicy.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(ImageType.PNG, AttachmentPolicy.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(ImageType.GIF, AttachmentPolicy.sniff(ascii("GIF89a")))
        assertEquals(ImageType.WEBP, AttachmentPolicy.sniff(ascii("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ")))
        assertEquals(ImageType.HEIC, AttachmentPolicy.sniff(ascii("\u0000\u0000\u0000\u0018ftypheic")))
    }

    @Test fun rejectsNonImagesEvenWithImageMime() {
        assertNull(AttachmentPolicy.sniff(ascii("<html><script>")))
        assertNull(AttachmentPolicy.sniff(ascii("%PDF-1.7")))
        assertNull(AttachmentPolicy.sniff(bytes(0x50, 0x4B, 0x03, 0x04))) // zip / apk
        assertNull(AttachmentPolicy.sniff(ByteArray(0)))
    }

    @Test fun enforcesSizeCap() {
        val jpeg = bytes(0xFF, 0xD8, 0xFF, 0xE0)
        assertEquals(ContentDecision.Accept(ImageType.JPEG), AttachmentPolicy.checkContent(jpeg, 1024))
        assertEquals(ContentDecision.Reject("too large"), AttachmentPolicy.checkContent(jpeg, AttachmentPolicy.MAX_BYTES + 1))
        assertEquals(ContentDecision.Reject("empty"), AttachmentPolicy.checkContent(jpeg, 0))
        assertEquals(ContentDecision.Reject("not a supported image"), AttachmentPolicy.checkContent(ascii("hello"), 10))
    }
}
