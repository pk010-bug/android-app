package app.privateinbox.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Teal40,
    onPrimary = Slate95,
    primaryContainer = Teal90,
    onPrimaryContainer = Teal10,
    secondary = Indigo40,
    onSecondary = Slate95,
    secondaryContainer = Indigo90,
    onSecondaryContainer = Indigo10,
    background = Slate95,
    onBackground = Slate10,
    surface = Slate95,
    onSurface = Slate10,
    error = Error40,
)

private val DarkColors = darkColorScheme(
    primary = Teal80,
    onPrimary = Teal10,
    primaryContainer = Teal30,
    onPrimaryContainer = Teal90,
    secondary = Indigo80,
    onSecondary = Indigo10,
    secondaryContainer = Indigo30,
    onSecondaryContainer = Indigo90,
    background = Slate10,
    onBackground = Slate90,
    surface = Slate20,
    onSurface = Slate90,
    error = Error80,
)

/**
 * Brand theme. Dynamic color is deliberately off so the privacy palette stays recognisable.
 */
@Composable
fun PrivateInboxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = PrivateInboxTypography,
        content = content,
    )
}
