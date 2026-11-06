package app.privateinbox.sms

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Proof that a human pressed Send in our composer.
 *
 * - Only the composer UI creates it ([fromSendButton]); LogHygiene/SendPathTest enforce that no
 *   other package references it or [SmsSender].
 * - Single use and short-lived, so it cannot be cached and replayed by background code.
 */
class UserSendConfirmation private constructor(private val createdAt: Long) {
    private val used = AtomicBoolean(false)

    internal fun consume(now: Long = SystemClock.elapsedRealtime()): Boolean =
        now - createdAt in 0..MAX_AGE_MS && used.compareAndSet(false, true)

    companion object {
        const val MAX_AGE_MS = 5_000L

        /** Call ONLY from a Send button onClick in ui/compose. */
        fun fromSendButton(): UserSendConfirmation = UserSendConfirmation(SystemClock.elapsedRealtime())
    }
}
