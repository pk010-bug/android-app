package app.privateinbox.sms

import app.privateinbox.data.db.ScheduledMessageEntity
import app.privateinbox.data.db.ScheduledStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ScheduledSendAuthorizerTest {
    private fun hmac(key: String) = ScheduledSendAuthorizer.MacProvider { data ->
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")); doFinal(data) }
    }
    private val auth = ScheduledSendAuthorizer(hmac("k1"))
    private val row = ScheduledMessageEntity(
        id = 7, recipients = "+15550001\n+15550002", body = "see you at 8", sendAt = 1_800_000_000_000,
        subscriptionId = 2, createdAt = 1, status = ScheduledStatus.PENDING, conversationId = 3,
    )
    private val signed = row.copy(authTag = auth.sign(row))

    @Test fun validTagVerifies() = assertTrue(auth.verify(signed))

    @Test fun unsignedRowRejected() = assertFalse(auth.verify(row))

    @Test fun anyTamperRejected() {
        listOf(
            signed.copy(body = "see you at 9"),
            signed.copy(recipients = "+15550001\n+15559999"),
            signed.copy(sendAt = signed.sendAt + 1),
            signed.copy(subscriptionId = 1),
            signed.copy(subscriptionId = null),
            signed.copy(id = 8),
            signed.copy(authTag = "not base64 !!"),
            signed.copy(authTag = signed.authTag.reversed()),
        ).forEach { assertFalse("accepted tampered $it", auth.verify(it)) }
    }

    @Test fun differentKeyRejected() = assertFalse(ScheduledSendAuthorizer(hmac("other")).verify(signed))

    @Test fun nonTagFieldsDontMatter() {
        // Status/createdAt/conversation aren't part of the authorisation.
        assertTrue(auth.verify(signed.copy(status = ScheduledStatus.SENT, createdAt = 99, conversationId = null)))
    }
}
