package dev.cyh.aamp.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Locale

/** A round icon button; [description] is what accessibility services read. */
@Composable
internal fun IconButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = LocalColors.current.text,
    background: Color = Color.Transparent,
    size: Dp = 48.dp,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painterResource(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (enabled) tint else tint.copy(alpha = DISABLED_ALPHA)),
            modifier = Modifier.size(size * ICON_FRACTION),
        )
    }
}

/** A pill-shaped text button: filled in the accent color, or a quieter one on the surface color. */
@Composable
internal fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, filled: Boolean = true) {
    val colors = LocalColors.current
    BasicText(
        text,
        style = colors.body.copy(color = if (filled) colors.onAccent else colors.accent, fontWeight = FontWeight.Medium),
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (filled) colors.accent else colors.surface)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    )
}

/** "3:07", or "1:02:45" from an hour on. */
internal fun formatDuration(ms: Long): String {
    val total = (ms.coerceAtLeast(0) + 500) / 1000
    val h = total / 3600
    val m = total / 60 % 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

private const val DISABLED_ALPHA = 0.38f
private const val ICON_FRACTION = 0.5f
