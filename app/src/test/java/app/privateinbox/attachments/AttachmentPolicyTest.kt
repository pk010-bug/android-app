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

    private fun reasonAs(uri: String, user: Int?) =
        (AttachmentPolicy.checkUri(uri, own, currentUserId = user) as UriDecision.Reject).reason

    // F-01 regression: the authority must be validated in its canonical (decoded) form.
    @Test fun rejectsPercentEncodedUserIdAuthority() {
        listOf(
            "content://0%40mms/part/1",                       // the reported bypass
            "content://0%40com.android.contacts/contacts/1/photo",
            "content://0%40MMS/part/1",                       // mixed case authority
            "content://0%40Mms/part/1",
            "CoNtEnT://0%40mms/part/1",                       // mixed case scheme
            "content://0%2540mms/part/1",                     // double encoding
            "content://0%252540mms/part/1",                   // triple encoding
            "content://%30%40mms/part/1",                     // every byte encoded
            "content://0%40%6d%6d%73/part/1",                 // encoded authority name, lowercase hex
            "content://0%40%6D%6D%73/part/1",                 // uppercase hex
            "content://%6d%6d%73/part/1",                     // encoded blocked authority, no user id
            "content://media%2fexternal/images/media/1",      // encoded slash in authority
            "content://evil%40app.privateinbox.fileprovider/x",
            "  content://0%40mms/part/1  ",                   // surrounding whitespace
        ).forEach { assertEquals(it, UriDecision.Reason.MALFORMED, reason(it)) }
        // Same when we know our user id: encoding is refused, never decoded-and-trusted.
        assertEquals(UriDecision.Reason.MALFORMED, reasonAs("content://0%40mms/part/1", 0))
        assertNull(AttachmentPolicy.canonicalAuthority("content://0%40mms/part/1", 0))
    }

    @Test fun userIdPrefixMustBeOurUser() {
        // Literal prefixes: another user, unknown user, or non-numeric "userinfo" are refused.
        assertEquals(UriDecision.Reason.OTHER_USER, reasonAs("content://10@media/external/images/media/1", 0))
        assertEquals(UriDecision.Reason.OTHER_USER, reasonAs("content://0@media/external/images/media/1", 10))
        assertEquals(UriDecision.Reason.OTHER_USER, reason("content://0@media/external/images/media/1"))
        assertEquals(UriDecision.Reason.MALFORMED, reasonAs("content://0@0@mms/part/1", 0))
        assertEquals(UriDecision.Reason.MALFORMED, reasonAs("content://-1@mms/part/1", 0))
        assertEquals(UriDecision.Reason.MALFORMED, reasonAs("content://+0@mms/part/1", 0))
        assertEquals(UriDecision.Reason.MALFORMED, reasonAs("content://@mms/part/1", 0))
        assertEquals(UriDecision.Reason.MISSING_AUTHORITY, reasonAs("content://0@/part/1", 0))
        // Our own user's prefix is stripped; the block/own lists then apply to the real authority.
        assertEquals(UriDecision.Reason.BLOCKED_PROVIDER, reasonAs("content://0@mms/part/1", 0))
        assertEquals(UriDecision.Reason.BLOCKED_PROVIDER, reasonAs("content://0@MMS/part/1", 0))
        assertEquals(UriDecision.Reason.BLOCKED_PROVIDER, reasonAs("content://10@com.android.contacts/contacts/1/photo", 10))
        assertEquals(UriDecision.Reason.OWN_PROVIDER, reasonAs("content://0@app.privateinbox.fileprovider/x", 0))
        assertEquals(UriDecision.Accept, AttachmentPolicy.checkUri("content://0@media/external/images/media/1", own, currentUserId = 0))
        assertEquals("media", AttachmentPolicy.canonicalAuthority("content://0@media/external/images/media/1", 0))
        assertEquals("com.Example.Provider", AttachmentPolicy.canonicalAuthority("content://com.Example.Provider/a", 0))
    }

    @Test fun rejectsAmbiguousAuthorityCharacters() {
        listOf(
            "content://mms\\@x/part/1",
            "content://m ms/part/1",
            "content://mms\t/part/1",
            "content://ｍｍｓ/part/1",                         // full-width look-alike
            "content://mms;evil/part/1",
            "content://mms\u0000/part/1",
        ).forEach { assertEquals(it, UriDecision.Reason.MALFORMED, reason(it)) }
        // Port / trailing-dot forms still hit the blocklist or (in the importer) fail to resolve.
        assertEquals(UriDecision.Reason.BLOCKED_PROVIDER, reason("content://MMS:0/part/1"))
    }

    @Test fun rejectsEncodedTraversalInPath() {
        listOf(
            "content://com.example.provider/a/../b",
            "content://com.example.provider/a/%2e%2e/b",
            "content://com.example.provider/a/%2E%2E/b",
            "content://com.example.provider/a/%252e%252e/b",
            "content://com.example.provider/a%2f..%2fb",
            "content://com.example.provider/a/%00.jpg",
        ).forEach { assertEquals(it, UriDecision.Reason.MALFORMED, reason(it)) }
    }

    @Test fun legitimateEncodedPathsStillAccepted() {
        listOf(
            "content://com.android.providers.media.documents/document/image%3A1234",
            "content://com.android.externalstorage.documents/document/primary%3ADCIM%2FCamera%2FIMG_1.jpg",
            "content://com.android.providers.downloads.documents/document/msf%3A42",
            "content://media/picker/0/com.android.providers.media.photopicker/media/1000000033",
            "content://com.example.provider/100%25.jpg",
            "content://com.example.provider/bad%zz.jpg",
            "content://com.example.provider/a..b/c.jpg",
        ).forEach { assertEquals(it, UriDecision.Accept, check(it)) }
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
