package app.privateinbox.sms

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.privateinbox.data.db.ScheduledMessageEntity
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * Binds a scheduled message to the tap that created it.
 *
 * When the user confirms "Schedule" in the composer, [SmsSender.schedule] consumes the
 * [UserSendConfirmation] and signs the exact (id, recipients, body, time, SIM) with a
 * non-exportable AndroidKeyStore HMAC key. The worker only sends rows whose tag verifies, so a
 * row inserted or edited by anything other than that code path (e.g. a tampered DB or restored
 * backup) is never sent.
 */
class ScheduledSendAuthorizer(private val mac: MacProvider = KeystoreMac()) {

    fun interface MacProvider { fun mac(data: ByteArray): ByteArray }

    fun sign(row: ScheduledMessageEntity): String =
        Base64.getEncoder().withoutPadding().encodeToString(mac.mac(canonical(row)))

    fun verify(row: ScheduledMessageEntity): Boolean {
        if (row.authTag.isEmpty()) return false
        val given = try { Base64.getDecoder().decode(row.authTag) } catch (_: IllegalArgumentException) { return false }
        return MessageDigest.isEqual(given, mac.mac(canonical(row)))
    }

    companion object {
        /** Length-prefixed fields so no two different rows share bytes. */
        fun canonical(row: ScheduledMessageEntity): ByteArray {
            val bodyHash = MessageDigest.getInstance("SHA-256").digest(row.body.toByteArray(Charsets.UTF_8))
            val fields = listOf(
                "v1".toByteArray(),
                row.id.toString().toByteArray(),
                row.recipients.toByteArray(Charsets.UTF_8),
                bodyHash,
                row.sendAt.toString().toByteArray(),
                (row.subscriptionId?.toString() ?: "-").toByteArray(),
            )
            val out = java.io.ByteArrayOutputStream()
            fields.forEach { f ->
                out.write(java.nio.ByteBuffer.allocate(4).putInt(f.size).array())
                out.write(f)
            }
            return out.toByteArray()
        }
    }
}

/** HmacSHA256 with a key that never leaves the AndroidKeyStore (TEE/StrongBox when present). */
class KeystoreMac(private val alias: String = "privateinbox_sched_v1") : ScheduledSendAuthorizer.MacProvider {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN).build())
        return gen.generateKey()
    }

    override fun mac(data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(key()); doFinal(data) }
}
