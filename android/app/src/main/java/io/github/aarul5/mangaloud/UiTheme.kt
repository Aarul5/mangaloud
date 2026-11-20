package io.github.aarul5.mangaloud

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The app's own colours: amber on near-black, from the icon. */
private val Amber = Color(0xFFFFB020)
private val Ink = Color(0xFF14141A)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF3A2600),
    primaryContainer = Color(0xFF5C3F00),
    onPrimaryContainer = Color(0xFFFFDDB0),
    secondary = Color(0xFFC8BFFF),
    onSecondary = Color(0xFF2A1F6B),
    secondaryContainer = Color(0xFF3F3485),
    onSecondaryContainer = Color(0xFFE5DEFF),
    background = Ink,
    onBackground = Color(0xFFECECF1),
    surface = Ink,
    onSurface = Color(0xFFECECF1),
    surfaceVariant = Color(0xFF2A2A33),
    onSurfaceVariant = Color(0xFFB8B8C4),
    surfaceContainerLowest = Color(0xFF0F0F14),
    surfaceContainerLow = Color(0xFF1A1A21),
    surfaceContainer = Color(0xFF1E1E26),
    surfaceContainerHigh = Color(0xFF25252E),
    surfaceContainerHighest = Color(0xFF2C2C36),
    outline = Color(0xFF6E6E7A),
    outlineVariant = Color(0xFF34343E),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF7A5200),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDB0),
    onPrimaryContainer = Color(0xFF281800),
    secondary = Color(0xFF5B4BDB),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5DEFF),
    onSecondaryContainer = Color(0xFF170765),
    background = Color(0xFFFCF8F3),
    onBackground = Color(0xFF1E1B16),
    surface = Color(0xFFFCF8F3),
    onSurface = Color(0xFF1E1B16),
    surfaceVariant = Color(0xFFEEE6DA),
    onSurfaceVariant = Color(0xFF4E463A),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F2EC),
    surfaceContainer = Color(0xFFF1ECE6),
    surfaceContainerHigh = Color(0xFFEBE6E0),
    surfaceContainerHighest = Color(0xFFE5E1DA),
    outline = Color(0xFF807667),
    outlineVariant = Color(0xFFD2C5B4),
)

@Composable
fun AppTheme(mode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}

/** The speech-bubble mark from the app icon. */
@Composable
fun LogoMark(size: Dp = 28.dp) {
    Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(size))
}

/** A rounded tile with the site's icon, or its first letter on a colour picked from its name. */
@Composable
fun SiteBadge(url: String, library: Library, size: Dp = 40.dp) {
    val icon = remember(url) { library.icon(url)?.asImageBitmap() }
    val shape = RoundedCornerShape(size * 0.28f)
    if (icon != null) {
        Box(
            Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Image(icon, contentDescription = null, modifier = Modifier.size(size * 0.62f))
        }
        return
    }
    val host = Library.hostOf(url)
    val letter = host.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?'
    val (bg, fg) = BADGE_COLOURS[Math.floorMod(host.hashCode(), BADGE_COLOURS.size)]
    Box(Modifier.size(size).clip(shape).background(bg), contentAlignment = Alignment.Center) {
        Text(letter.toString(), color = fg, fontWeight = FontWeight.Medium, fontSize = (size.value * 0.42f).sp)
    }
}

private val BADGE_COLOURS = listOf(
    Color(0xFFFFB020) to Color(0xFF3A2600),
    Color(0xFF5B4BDB) to Color.White,
    Color(0xFF1D9E75) to Color.White,
    Color(0xFFD85A30) to Color.White,
    Color(0xFF378ADD) to Color.White,
    Color(0xFFD4537E) to Color.White,
)

/**
 * The pill-shaped address and search field. Shows [shown] (the page's address) until it is
 * tapped, then lets you type; Go opens what was typed.
 */
@Composable
fun AddressField(
    shown: String,
    placeholder: String,
    onGo: (String) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
) {
    var focused by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(TextFieldValue(shown)) }
    LaunchedEffect(shown, focused) {
        if (!focused) value = TextFieldValue(shown)
    }
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.height(height).fillMaxWidth(),
        shape = RoundedCornerShape(height / 2),
        color = colors.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Box(Modifier.padding(start = 10.dp).weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.text.isEmpty()) {
                    Text(placeholder, color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                }
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        focus.clearFocus()
                        onGo(value.text)
                    }),
                    modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
                        if (state.isFocused && !focused) value = value.copy(selection = TextRange(0, value.text.length))
                        focused = state.isFocused
                    },
                )
            }
        }
    }
}

/** A section heading inside a list. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 8.dp),
    )
}
