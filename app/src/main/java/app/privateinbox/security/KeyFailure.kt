package app.privateinbox.security

/**
 * F-13 / RB-2: is a Keystore / unwrap failure a DEFINITE key loss, or possibly transient?
 * Pure; matched by class name (including superclasses) so it can be unit-tested on the JVM without
 * Android classes.
 *
 * The cause chain is walked from the outermost exception; the first recognised entry decides:
 *  - Definite: AEADBadTagException (the GCM tag doesn't verify: different key or corrupted file;
 *    AndroidKeyStore raises it for KM_ERROR_VERIFICATION_FAILED, with a KeyStoreException cause),
 *    KeyPermanentlyInvalidatedException, and a bare UnrecoverableKeyException with no cause.
 *  - Transient: KeyStoreException (java or android), ProviderException, RemoteException /
 *    DeadObjectException, IOException. In particular AndroidKeyStore wraps any other Keystore error
 *    from doFinal as IllegalBlockSizeException(cause = KeyStoreException), so
 *    IllegalBlockSizeException (and BadPaddingException, InvalidKeyException, ...) is never proof of
 *    key loss on its own.
 *  - Anything unrecognised is treated as transient.
 *
 * Even a "definite" result never deletes anything by itself: it only changes what the recovery
 * screen says (see PrivateInboxDatabase.resetAfterUserConfirmation).
 */
object KeyFailure {
    private const val UNRECOVERABLE = "java.security.UnrecoverableKeyException"

    private val DEFINITE = setOf(
        "javax.crypto.AEADBadTagException",
        "android.security.keystore.KeyPermanentlyInvalidatedException",
    )

    private val TRANSIENT = setOf(
        "java.security.KeyStoreException",
        "android.security.KeyStoreException",
        "java.security.ProviderException",
        "android.os.RemoteException",
        "java.io.IOException",
    )

    fun isPermanent(t: Throwable): Boolean {
        var c: Throwable? = t
        var depth = 0
        while (c != null && depth++ < 8) {
            val names = classNames(c.javaClass)
            if (names.any { it in DEFINITE }) return true
            if (names.any { it in TRANSIENT }) return false
            if (c.javaClass.name == UNRECOVERABLE) return c.cause == null
            c = c.cause
        }
        return false
    }

    private fun classNames(k: Class<*>): List<String> =
        generateSequence(k) { it.superclass }.takeWhile { it != Any::class.java }.map { it.name }.toList()
}
