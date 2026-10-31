package com.kramttocs.lubelink.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.Button as MaterialButton
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.TextButton as MaterialTextButton

// Material 3 draws its buttons fully round whatever the theme says, so the app's buttons are these, which take the
// chosen corner style (OD-53) and are otherwise Material's own. Screens import these, never Material's buttons.

@Composable
internal fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                    shape: Shape = controlShape, colors: ButtonColors = ButtonDefaults.buttonColors(),
                    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(), border: BorderStroke? = null,
                    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
                    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit) =
    MaterialButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource,
        content)

@Composable
internal fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                            shape: Shape = controlShape, colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
                            elevation: ButtonElevation? = null,
                            border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
                            contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
                            interactionSource: MutableInteractionSource? = null,
                            content: @Composable RowScope.() -> Unit) =
    MaterialOutlinedButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding,
        interactionSource, content)

@Composable
internal fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
                        shape: Shape = controlShape, colors: ButtonColors = ButtonDefaults.textButtonColors(),
                        elevation: ButtonElevation? = null, border: BorderStroke? = null,
                        contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
                        interactionSource: MutableInteractionSource? = null,
                        content: @Composable RowScope.() -> Unit) =
    MaterialTextButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource,
        content)
