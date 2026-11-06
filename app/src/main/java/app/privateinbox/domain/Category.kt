package app.privateinbox.domain

/**
 * Smart-inbox buckets. Every message ends up in exactly one category.
 * Order here is also the display order of the inbox pills.
 */
enum class Category(val label: String) {
    IMPORTANT("Important"),
    PERSONAL("Personal"),
    OTP("OTP"),
    PROMOTIONAL("Promotional"),
    SPAM("Spam"),
    UNKNOWN("Unknown");

    companion object {
        /** Lenient lookup used when parsing rule JSON ("otp", "Otp", "OTP" all work). */
        fun fromName(name: String): Category? =
            entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}
