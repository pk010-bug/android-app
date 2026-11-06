package app.privateinbox.data.rules

import app.privateinbox.domain.Classification
import app.privateinbox.domain.SmsMessage

/**
 * Decides which [app.privateinbox.domain.Category] a message belongs to.
 * Implementations must be pure and on-device: no I/O beyond reading local rules.
 */
interface RuleEngine {
    fun classify(message: SmsMessage): Classification
}
