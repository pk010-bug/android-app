package app.privateinbox.attachments

import app.privateinbox.attachments.AttachmentPolicy.ProviderFacts
import org.junit.Assert.assertEquals
import org.junit.Test

/** Confused-deputy rules for URIs another app shares with us. */
class AttachmentSourceTest {
    private val own = setOf("app.privateinbox.fileprovider")
    private fun reason(uri: String) = (AttachmentPolicy.checkUri(uri, own) as UriDecision.Reject).reason
    private val ourPerms = setOf("android.permission.READ_SMS", "android.permission.READ_CONTACTS")
    private fun src(p: ProviderFacts?, granted: Boolean) = AttachmentPolicy.checkSource(p, { it in ourPerms }, granted)

    @Test fun personalDataProvidersAreBlocked() {
        listOf(
            "content://mms/part/12", "content://sms/inbox/1", "content://mms-sms/conversations",
            "content://com.android.contacts/contacts/1/photo", "content://contacts/people/1",
            "content://call_log/calls", "content://telephony/carriers", "content://MMS/part/1",
            "content://mms:0/part/1",
        ).forEach { assertEquals(it, UriDecision.Reason.BLOCKED_PROVIDER, reason(it)) }
        // user-prefixed form is rejected as malformed before the blocklist
        assertEquals(UriDecision.Reason.MALFORMED, reason("content://0@mms/part/1"))
    }

    @Test fun publicProviderNeedsNoGrant() {
        assertEquals(UriDecision.Accept, src(ProviderFacts(exported = true, readPermission = null), granted = false))
    }

    @Test fun providerGuardedByOurOwnPermissionIsRefusedEvenWithGrant() {
        val p = ProviderFacts(exported = true, readPermission = "android.permission.READ_SMS")
        assertEquals(UriDecision.Reject(UriDecision.Reason.PRIVILEGED_PROVIDER), src(p, granted = true))
        val path = ProviderFacts(exported = true, readPermission = null, pathReadPermissions = listOf("android.permission.READ_CONTACTS"))
        assertEquals(UriDecision.Reject(UriDecision.Reason.PRIVILEGED_PROVIDER), src(path, granted = true))
    }

    @Test fun protectedProviderRequiresCallerGrant() {
        val p = ProviderFacts(exported = false, readPermission = null)
        assertEquals(UriDecision.Reject(UriDecision.Reason.NOT_GRANTED), src(p, granted = false))
        assertEquals(UriDecision.Accept, src(p, granted = true))
        val sig = ProviderFacts(exported = true, readPermission = "com.other.READ")
        assertEquals(UriDecision.Reject(UriDecision.Reason.NOT_GRANTED), src(sig, granted = false))
        assertEquals(UriDecision.Accept, src(sig, granted = true))
    }

    @Test fun unresolvableProviderRequiresGrant() {
        assertEquals(UriDecision.Reject(UriDecision.Reason.NOT_GRANTED), src(null, granted = false))
        assertEquals(UriDecision.Accept, src(null, granted = true))
    }
}
