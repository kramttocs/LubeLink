package com.kramttocs.lubelink.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kramttocs.lubelink.core.CornerStyle

val Gold = Color(0xFFF3BE26)
val Charcoal = Color(0xFF202329)
val Panel = Color(0xFF2D3138)
private val scheme = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF17191C),
    background = Charcoal,
    onBackground = Color(0xFFF4F4F4),
    surface = Charcoal,
    onSurface = Color(0xFFF4F4F4),
    surfaceVariant = Panel,
    onSurfaceVariant = Color(0xFFCED2D6),
    secondary = Gold,
    outline = Color(0xFF5B6068)
)

/**
 * Corners (OD-53): Rounded uses SheepKeeper's radii, Square none. Screens use only the shape names below, never their
 * own corners, so the choice reaches every screen; Material components take theirs from [material]. Always round in
 * both styles: status pills, Calendar dots and progress bars.
 */
internal class Corners(val controlRadius: Dp, val panelRadius: Dp, val largeRadius: Dp) {
    val control = RoundedCornerShape(controlRadius)   // buttons, fields, chips, menus, small cells
    val panel = RoundedCornerShape(panelRadius)       // cards, panels, rows, the snackbar, pictures
    val large = RoundedCornerShape(largeRadius)       // dialogs
    val material = Shapes(extraSmall = control, small = control, medium = panel, large = large, extraLarge = large)
}

internal val roundedCorners = Corners(controlRadius = 6.dp, panelRadius = 9.dp, largeRadius = 12.dp)
internal val squareCorners = Corners(controlRadius = 0.dp, panelRadius = 0.dp, largeRadius = 0.dp)
internal fun cornersFor(style: CornerStyle) = when (style) {
    CornerStyle.ROUNDED -> roundedCorners
    CornerStyle.SQUARE -> squareCorners
}
internal val LocalCorners = staticCompositionLocalOf { roundedCorners }
internal val controlShape: Shape @Composable @ReadOnlyComposable get() = LocalCorners.current.control
internal val panelShape: Shape @Composable @ReadOnlyComposable get() = LocalCorners.current.panel

/** Always round, in both styles (OD-53 covers buttons, fields, chips, cards and dialogs, not these). */
internal val statusPillShape = RoundedCornerShape(20.dp)   // the urgency label on a reminder
internal val progressBarShape = RoundedCornerShape(10.dp)  // Reports' share bars
internal val dotShape = CircleShape                         // Calendar's day markers

/** Gives [content] the chosen corners, for the shape names above and for every Material component. */
@Composable
internal fun CornerTheme(style: CornerStyle, content: @Composable () -> Unit) {
    val corners = cornersFor(style)
    CompositionLocalProvider(LocalCorners provides corners) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme, typography = MaterialTheme.typography,
            shapes = corners.material, content = content)
    }
}

@Composable
fun LubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme) { CornerTheme(CornerStyle.ROUNDED, content) }
}
