package app.privateinbox.data.rules

import android.content.Context

/** Loads the bundled starter rules from assets. Purely local file access. */
object AssetRuleLoader {
    const val DEFAULT_RULES_ASSET = "default_rules.json"

    fun loadDefault(context: Context): RegexRuleEngine {
        val text = context.assets.open(DEFAULT_RULES_ASSET).bufferedReader().use { it.readText() }
        return RegexRuleEngine.fromJson(text)
    }
}
