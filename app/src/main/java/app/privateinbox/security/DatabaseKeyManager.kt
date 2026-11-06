package app.privateinbox.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Provides the SQLCipher passphrase for the Room database.
 *
 * - A random 256-bit passphrase is generated once with SecureRandom.
 * - It is stored only in wrapped form (AES-256-GCM) under noBackupFilesDir; the wrapping key
 *   lives in the AndroidKeyStore (hardware-backed where the device supports it) and never
 *   leaves it.
 * - The key is NOT bound to device unlock / biometrics, because SMS must be stored while the
 *   phone is locked. App-lock is a separate UI gate.
 */
class DatabaseKeyManager(private val context: Context) {

    private val file: File get() = File(context.noBackupFilesDir, WRAPPED_FILE)

    /** Returns the passphrase, creating it on first use. Caller should wipe the array after use. */
    @Synchronized
    fun getOrCreatePassphrase(): ByteArray {
        if (file.exists()) {
            val wrapped = WrappedKeyCodec.decode(file.readBytes())
                ?: throw KeyUnavailableException("wrapped key file is corrupt")
            return try {
                unwrap(wrapped)
            } catch (e: Exception) {
                throw KeyUnavailableException("keystore unwrap failed", e)
            }
        }
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapped = wrap(passphrase)
        val tmp = File(file.parentFile, "$WRAPPED_FILE.tmp")
        tmp.writeBytes(WrappedKeyCodec.encode(wrapped))
        check(tmp.renameTo(file)) { "could not persist wrapped key" }
        return passphrase
    }

    /** Destroys the passphrase and its wrapping key (makes the existing DB unreadable). */
    @Synchronized
    fun destroy() {
        file.delete()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun wrappingKey(): SecretKey {
        (keyStore().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    private fun wrap(plain: ByteArray): WrappedKeyCodec.Wrapped {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        return WrappedKeyCodec.Wrapped(cipher.iv, cipher.doFinal(plain))
    }

    private fun unwrap(w: WrappedKeyCodec.Wrapped): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, w.iv))
        return cipher.doFinal(w.ciphertext)
    }

    class KeyUnavailableException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "privateinbox_db_kek_v1"
        const val WRAPPED_FILE = "db_passphrase.v1.bin"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
