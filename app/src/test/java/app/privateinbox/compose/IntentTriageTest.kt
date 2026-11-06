package app.privateinbox.compose

import app.privateinbox.attachments.UriDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentTriageTest {
    private val own = setOf("app.privateinbox.fileprovider")

    private fun prefill(i: ExternalIntent): ComposePrefill {
        val r = IntentTriage.triage(i, own)
        assertTrue("expected Prefill, got $r", r is TriageResult.Prefill)
        return (r as TriageResult.Prefill).prefill
    }

    @Test fun smstoUriPrefillsRecipientAndBody() {
        val p = prefill(ExternalIntent(IntentTriage.ACTION_SENDTO, dataUri = "smsto:+919876543210?body=Hello%20there"))
        assertEquals(listOf("+919876543210"), p.recipients)
        assertEquals("Hello there", p.body)
        assertFalse(p.autoSendIgnored)
    }

    @Test fun multipleRecipientsAndSmsBodyExtra() {
        val p = prefill(
            ExternalIntent(IntentTriage.ACTION_VIEW, dataUri = "sms:111222333,444555666", extraSmsBody = "hi"),
        )
        assertEquals(listOf("111222333", "444555666"), p.recipients)
        assertEquals("hi", p.body)
    }

    @Test fun autoSendExtrasAreDroppedAndFlagged() {
        val p = prefill(
            ExternalIntent(
                IntentTriage.ACTION_SENDTO,
                dataUri = "smsto:12345",
                extraSmsBody = "pay me",
                extraKeys = setOf("sms_body", "exit_on_sent", "SEND"),
            ),
        )
        assertTrue(p.autoSendIgnored)
        // The result type has no way to express "send"; it is a draft only.
        assertEquals("pay me", p.body)
    }

    @Test fun shareTextBecomesDraftWithoutRecipients() {
        val p = prefill(ExternalIntent(IntentTriage.ACTION_SEND, mimeType = "text/plain", extraText = "look at this"))
        assertTrue(p.recipients.isEmpty())
        assertEquals("look at this", p.body)
    }

    @Test fun shareAttachmentsAreFilteredByPolicy() {
        val p = prefill(
            ExternalIntent(
                IntentTriage.ACTION_SEND_MULTIPLE,
                mimeType = "image/*",
                streamUris = listOf(
                    "content://com.android.providers.media.documents/document/image%3A1",
                    "file:///data/data/app.privateinbox/databases/privateinbox.db",
                    "content://app.privateinbox.fileprovider/outbound_share/x.png",
                ),
            ),
        )
        assertEquals(1, p.attachmentUris.size)
        assertEquals(
            listOf(UriDecision.Reason.NOT_CONTENT_SCHEME, UriDecision.Reason.OWN_PROVIDER),
            p.rejectedAttachments.map { it.second.reason },
        )
    }

    @Test fun nonSmsSchemesAndUnknownActionsAreIgnored() {
        assertTrue(IntentTriage.triage(ExternalIntent(IntentTriage.ACTION_SENDTO, dataUri = "tel:123"), own) is TriageResult.Ignore)
        assertTrue(IntentTriage.triage(ExternalIntent(IntentTriage.ACTION_SENDTO, dataUri = null), own) is TriageResult.Ignore)
        assertTrue(IntentTriage.triage(ExternalIntent("android.intent.action.CALL"), own) is TriageResult.Ignore)
        assertEquals(TriageResult.OpenApp, IntentTriage.triage(ExternalIntent(IntentTriage.ACTION_MAIN), own))
    }

    @Test fun hostileRecipientsAndHugeBodiesAreBounded() {
        val p = prefill(
            ExternalIntent(
                IntentTriage.ACTION_SENDTO,
                dataUri = "smsto:<script>,12345",
                extraSmsBody = "x".repeat(50_000),
            ),
        )
        assertEquals(listOf("12345"), p.recipients)
        assertEquals(IntentTriage.MAX_BODY_CHARS, p.body.length)
    }
}
