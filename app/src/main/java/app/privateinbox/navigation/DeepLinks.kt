package app.privateinbox.navigation

/**
 * Strict allow-list for in-app routes that can arrive from outside the composition
 * (notification taps, restored state). Anything not on the list is dropped.
 * We register NO http(s)/custom-scheme deep links in the manifest.
 */
object DeepLinks {
    const val EXTRA_ROUTE = "app.privateinbox.extra.ROUTE"

    private val STATIC = setOf("inbox", "finance", "alerts", "settings", "compose", "scheduled")
    private val CONVERSATION = Regex("^conversation/([1-9][0-9]{0,17})$")

    fun validate(route: String?): String? {
        if (route == null || route.length > 40) return null
        if (route in STATIC) return route
        val m = CONVERSATION.matchEntire(route) ?: return null
        return m.groupValues[1].toLongOrNull()?.let { "conversation/$it" }
    }

    fun conversationId(route: String): Long? =
        CONVERSATION.matchEntire(route)?.groupValues?.get(1)?.toLongOrNull()
}
