package app.privateinbox.security

import android.util.Log

/**
 * The ONLY logging entry point allowed in this codebase (enforced by LogHygieneTest).
 *
 * Rules:
 *  - Log events and counts, never content: no message body, sender/address, contact name,
 *    OTP, amount, or account number.
 *  - Messages are passed through [Redactor] anyway.
 *  - Verbose/debug output is dropped unless the tag is explicitly enabled via
 *    `adb shell setprop log.tag.<TAG> DEBUG` (Log.isLoggable).
 */
object SafeLog {
    fun d(tag: String, message: String) {
        if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, Redactor.redact(message))
    }

    fun i(tag: String, message: String) {
        Log.i(tag, Redactor.redact(message))
    }

    fun w(tag: String, message: String) {
        Log.w(tag, Redactor.redact(message))
    }

    /** Only the throwable's class is logged; its message may contain user data. */
    fun e(tag: String, message: String, error: Throwable? = null) {
        val suffix = error?.let { " (${it.javaClass.simpleName})" }.orEmpty()
        Log.e(tag, Redactor.redact(message) + suffix)
    }
}
