package app.privateinbox.data.rules

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Human-readable rule file format (see assets/default_rules.json).
 *
 * A rule matches when ALL of its non-empty conditions match:
 *  - [senderPatterns]: at least one regex matches the sender address
 *  - [bodyPatterns]: at least one regex matches the message body
 *  - [excludeBodyPatterns]: none of these regexes match the body
 * Rules are tried by descending [priority]; the first match wins.
 */
@Serializable
data class RuleSet(
    val version: Int = 1,
    val rules: List<RuleSpec> = emptyList(),
)

@Serializable
data class RuleSpec(
    val id: String,
    val category: String,
    val description: String = "",
    val priority: Int = 0,
    val enabled: Boolean = true,
    @SerialName("senderPatterns") val senderPatterns: List<String> = emptyList(),
    @SerialName("bodyPatterns") val bodyPatterns: List<String> = emptyList(),
    @SerialName("excludeBodyPatterns") val excludeBodyPatterns: List<String> = emptyList(),
)

object RuleSetParser {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = false
    }

    fun parse(text: String): RuleSet = json.decodeFromString(RuleSet.serializer(), text)
}
