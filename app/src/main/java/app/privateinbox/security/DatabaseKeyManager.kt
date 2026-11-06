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

    /**
     * Returns the passphrase, creating it on first use. Caller should wipe the array after use.
     *
     * F-13: failures are split in two. [KeyUnavailableException] means the key is PERMANENTLY gone
     * (corrupt wrapped file, wrapping key missing or invalidated, authentication tag mismatch): the
     * encrypted database can never be opened again. [KeyTemporarilyUnavailableException] is anything
     * else (Keystore daemon busy or not ready yet, I/O error): the caller must retry later and must
     * NOT delete anything.
     */
    @Synchronized
    fun getOrCreatePassphrase(): ByteArray {
        if (file.exists()) {
            val bytes = try {
                file.readBytes()
            } catch (e: java.io.IOException) {
                throw KeyTemporarilyUnavailableException("could not read wrapped key", e)
            }
            val wrapped = WrappedKeyCodec.decode(bytes)
                ?: throw KeyUnavailableException("wrapped key file is corrupt")
            val key = try {
                existingWrappingKey()
            } catch (e: Exception) {
                if (KeyFailure.isPermanent(e)) throw KeyUnavailableException("wrapping key unusable", e)
                throw KeyTemporarilyUnavailableException("keystore not available", e)
            } ?: throw KeyUnavailableException("wrapping key missing")
            return try {
                unwrap(key, wrapped)
            } catch (e: Exception) {
                if (KeyFailure.isPermanent(e)) throw KeyUnavailableException("keystore unwrap failed", e)
                throw KeyTemporarilyUnavailableException("keystore unwrap failed (transient)", e)
            }
        }
        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapped = wrap(passphrase)
        val tmp = File(file.parentFile, "$WRAPPED_FILE.tmp")
        tmp.writeBytes(WrappedKeyCodec.encode(wrapped))
        check(tmp.renameTo(file)) { "could not persist wrapped key" }
        return passphrase
    }

    /** True if a wrapped passphrase file exists (without it, an existing database can't be opened). */
    fun hasWrappedKey(): Boolean = file.exists()

    /**
     * Destroys the passphrase and its wrapping key (makes the existing DB unreadable). Only for a
     * user-confirmed reset (PrivateInboxDatabase.resetAfterUserConfirmation).
     */
    @Synchronized
    fun destroy() {
        file.delete()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    /** The wrapping key if it exists; never creates one (a new key could not unwrap the old file). */
    private fun existingWrappingKey(): SecretKey? {
        val ks = keyStore()
        if (!ks.containsAlias(KEY_ALIAS)) return null
        return (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            ?: throw java.security.UnrecoverableKeyException("entry is not a secret key")
    }

    private fun wrappingKey(): SecretKey {
        existingWrappingKey()?.let { return it }
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

    private fun unwrap(key: SecretKey, w: WrappedKeyCodec.Wrapped): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, w.iv))
        return cipher.doFinal(w.ciphertext)
    }

    /**
     * The key looks permanently lost (corrupt wrapped file, missing or invalidated Keystore key,
     * tag mismatch). Nothing is deleted automatically: the user decides (RB-2).
     */
    class KeyUnavailableException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    /** The Keystore failed for a reason that may go away (retry; never wipe on this). */
    class KeyTemporarilyUnavailableException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "privateinbox_db_kek_v1"
        const val WRAPPED_FILE = "db_passphrase.v1.bin"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
