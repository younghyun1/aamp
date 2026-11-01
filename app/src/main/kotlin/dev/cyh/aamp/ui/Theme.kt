package dev.cyh.aamp.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** The phone screen's colors, taken from Android's wallpaper-derived system palette. */
@Immutable
internal class AampColors(
    val background: Color,
    val surface: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val placeholder: Color,
) {
    val title = TextStyle(color = text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    val body = TextStyle(color = text, fontSize = 14.sp)
    val caption = TextStyle(color = muted, fontSize = 12.sp)
    val heading = TextStyle(color = text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    val mono = TextStyle(color = text, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
}

internal val LocalColors = staticCompositionLocalOf<AampColors> { error("AampTheme is missing") }

@Composable
internal fun AampTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        AampColors(
            background = colorResource(android.R.color.system_neutral1_900),
            surface = colorResource(android.R.color.system_neutral1_800),
            text = colorResource(android.R.color.system_neutral1_50),
            muted = colorResource(android.R.color.system_neutral2_300),
            accent = colorResource(android.R.color.system_accent1_200),
            onAccent = colorResource(android.R.color.system_accent1_800),
            placeholder = colorResource(android.R.color.system_neutral2_700),
        )
    } else {
        AampColors(
            background = colorResource(android.R.color.system_neutral1_10),
            surface = colorResource(android.R.color.system_accent2_50),
            text = colorResource(android.R.color.system_neutral1_900),
            muted = colorResource(android.R.color.system_neutral2_600),
            accent = colorResource(android.R.color.system_accent1_600),
            onAccent = colorResource(android.R.color.system_accent1_0),
            placeholder = colorResource(android.R.color.system_neutral2_100),
        )
    }
    CompositionLocalProvider(LocalColors provides colors, content = content)
}
