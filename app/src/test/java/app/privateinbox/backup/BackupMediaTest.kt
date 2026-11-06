package app.privateinbox.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class BackupMediaTest {
    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    @Test fun validMediaRestores() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        val d = BackupMedia.decode(BMmsPart(1, "image/whatever", data = b64(jpeg)))!!
        assertEquals("image/jpeg", d.mimeType) // declared type ignored; sniffed type wins
        assertEquals("jpg", d.extension)
    }

    @Test fun nonMediaOrGarbageRejected() {
        assertNull(BackupMedia.decode(BMmsPart(1, "image/jpeg", data = b64("PK\u0003\u0004apk".toByteArray()))))
        assertNull(BackupMedia.decode(BMmsPart(1, "image/jpeg", data = "!!not base64!!")))
        assertNull(BackupMedia.decode(BMmsPart(1, "image/jpeg")))
        assertNotNull(BackupMedia.decode(BMmsPart(1, "text/plain", text = "hi")))
    }

    @Test fun v1BackupJsonStillDecodes() {
        val v1 = """{"format":1,"createdAt":1,"conversations":[],"messages":[{"id":1,"conversationId":1,"address":"a","body":"b","timestamp":1,"incoming":true,"read":true,"status":"RECEIVED"}],"rules":[],"blocked":[],"paidBillMessageIds":[1]}"""
        val p = BackupJson.decode(v1.toByteArray())
        assertEquals(1, p.format)
        assertEquals(false, p.messages.single().isMms)
        assertEquals(emptyList<BMmsPart>(), p.mmsParts)
    }

    @Test fun roundTripKeepsMmsFields() {
        val p = BackupPayload(
            createdAt = 1, conversations = emptyList(),
            messages = listOf(BMessage(1, 1, "a", "", 1, true, true, "RECEIVED", isMms = true, mmsSubject = "s")),
            rules = emptyList(), blocked = emptyList(), paidBillMessageIds = listOf(1),
            mmsParts = listOf(BMmsPart(1, "image/png", data = "AA==")),
        )
        val back = BackupJson.decode(BackupJson.encode(p))
        assertEquals(p, back)
        assertEquals(2, back.format)
    }
}
