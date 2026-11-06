package app.privateinbox.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.privateinbox.AppContainer
import app.privateinbox.domain.Category
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

object Format {
    private val time = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val full = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.getDefault())
    private val inr = NumberFormat.getCurrencyInstance(Locale("en", "IN"))

    fun shortTime(millis: Long): String {
        val z = ZoneId.systemDefault()
        val d = Instant.ofEpochMilli(millis).atZone(z)
        val today = LocalDate.now(z)
        return when {
            d.toLocalDate() == today -> d.format(time)
            d.year == today.year -> d.format(dayMonth)
            else -> d.format(DateTimeFormatter.ofPattern("d MMM yy"))
        }
    }

    fun fullTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(full)

    fun date(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(dayMonth)

    fun money(minor: Long): String = inr.format(minor / 100.0)
}

@Composable
fun categoryColor(category: Category): Color = when (category) {
    Category.IMPORTANT -> MaterialTheme.colorScheme.primary
    Category.PERSONAL -> MaterialTheme.colorScheme.secondary
    Category.OTP -> Color(0xFF00897B)
    Category.PROMOTIONAL -> Color(0xFFF9A825)
    Category.SPAM -> MaterialTheme.colorScheme.error
    Category.UNKNOWN -> MaterialTheme.colorScheme.outline
}

@Composable
fun Avatar(name: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "#",
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(CircleShape).background(color.copy(alpha = 0.15f)).padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text, color = color, style = MaterialTheme.typography.labelSmall)
    }
}

/** Standard secondary screen: top bar with back arrow + content. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(
    title: String,
    onBack: () -> Unit,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
        androidx.compose.material3.TopAppBar(
            title = { Text(title) },
            navigationIcon = {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    androidx.compose.material3.Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
            },
            actions = actions,
        )
        content()
    }
}

