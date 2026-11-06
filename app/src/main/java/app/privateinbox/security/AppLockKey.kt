package app.privateinbox.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.annotation.RequiresApi
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Keystore gate for the app lock (F-02, API 30+). An AES key that the AndroidKeyStore will only
 * use right after a strong biometric or device-credential authentication
 * (setUserAuthenticationRequired + per-use auth). The unlock prompt carries a cipher on this key as
 * its CryptoObject, and unlock proceeds only if that cipher then actually works, so a spoofed or
 * hooked "success" callback alone no longer unlocks.
 *
 * Deliberately NOT used to protect any data: the database key stays independent of user auth
 * because SMS must be stored while the phone is locked, and binding it could lock users out.
 * Any failure to create or use the key falls back to the plain prompt instead of locking the user out.
 */
@RequiresApi(Build.VERSION_CODES.R)
object AppLockKey {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "privateinbox_app_lock_gate"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    /** A cipher ready for a CryptoObject, or null if the gate can't be set up on this device. */
    fun cipherOrNull(): Cipher? = try {
        initCipher()
    } catch (e: KeyPermanentlyInvalidatedException) {
        // Screen lock was removed/reset: the old key is dead. Recreate it (auth is still required).
        delete()
        runCatching { initCipher() }.getOrNull()
    } catch (e: Exception) {
        SafeLog.w(TAG, "app-lock key unavailable: ${e.javaClass.simpleName}")
        null
    }

    /** True only if the authenticated cipher can actually be used (i.e. the Keystore saw a real auth). */
    fun verify(cipher: Cipher?): Boolean = cipher != null && runCatching { cipher.doFinal(ByteArray(16)) }.isSuccess

    fun delete() {
        runCatching { keyStore().deleteEntry(ALIAS) }
    }

    private fun initCipher(): Cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun key(): SecretKey {
        (keyStore().getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                // 0 = every use needs a fresh auth; Class 3 biometric or PIN/pattern/password only.
                .setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
                // Enrolling a new fingerprint already requires the device credential; don't churn the key.
                .setInvalidatedByBiometricEnrollment(false)
                .build(),
        )
        return gen.generateKey()
    }

    private const val TAG = "AppLockKey"
}
