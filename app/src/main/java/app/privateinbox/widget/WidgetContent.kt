package app.privateinbox.widget

import app.privateinbox.domain.Category

/**
 * Pure widget model (unit-tested). Inputs are counts and contact display names only; there is
 * deliberately no parameter through which message text or OTP codes could reach the widget.
 */
object WidgetContent {
    data class Model(val total: String, val categories: String, val recent: String?)

    fun build(
        appLocked: Boolean,
        showNames: Boolean,
        unreadByCategory: Map<Category, Int>,
        unreadNames: List<String>,
    ): Model {
        val total = unreadByCategory.values.sum()
        val totalText = when (total) {
            0 -> "No unread"
            1 -> "1 unread"
            else -> "$total unread"
        }
        if (appLocked) return Model(totalText, "Locked · open to view", null)
        val cats = ORDER.mapNotNull { c -> unreadByCategory[c]?.takeIf { it > 0 }?.let { "${c.label} $it" } }
            .joinToString(" · ").ifEmpty { "All caught up" }
        val recent = if (showNames && unreadNames.isNotEmpty()) unreadNames.take(3).joinToString(", ") { it.take(24) } else null
        return Model(totalText, cats, recent)
    }

    private val ORDER = listOf(Category.PERSONAL, Category.IMPORTANT, Category.OTP, Category.UNKNOWN, Category.PROMOTIONAL)
}
