package app.privateinbox.security

/**
 * F-13: is a Keystore / unwrap failure permanent (the key is gone for good) or transient?
 * Pure; matched by class name so it can be unit-tested on the JVM without Android classes.
 *
 * Permanent: the GCM tag doesn't verify (AEADBadTagException: different key or corrupted file),
 * the key was invalidated (KeyPermanentlyInvalidatedException) or can't be recovered
 * (UnrecoverableKeyException), or the stored IV/ciphertext is malformed. Everything else
 * (KeyStoreException, ProviderException, "Keystore not initialized", I/O, ...) is transient.
 */
object KeyFailure {
    private val PERMANENT = setOf(
        "javax.crypto.AEADBadTagException",
        "android.security.keystore.KeyPermanentlyInvalidatedException",
        "java.security.UnrecoverableKeyException",
        "java.security.UnrecoverableEntryException",
        "java.security.InvalidAlgorithmParameterException",
        "javax.crypto.IllegalBlockSizeException",
    )

    fun isPermanent(t: Throwable): Boolean {
        var c: Throwable? = t
        var depth = 0
        while (c != null && depth++ < 8) {
            if (c.javaClass.name in PERMANENT) return true
            c = c.cause
        }
        return false
    }
}
