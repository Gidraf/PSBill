package com.example.psbill

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/*
 * Text fields on the TV. The screens are dark but the fields are phone Material3
 * fields, whose default text colour is near-black: what you typed was invisible.
 * And a single-line field kept the D-pad focus after typing, so the remote could
 * not reach the next field or the button. Every TV text field uses these.
 */

private val TvFieldAccent = Color(0xFF818CF8)

@Composable
fun tvFieldColors(accent: Color = TvFieldAccent): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    disabledTextColor = Color(0xFFCBD5E1),
    focusedContainerColor = Color(0x1AFFFFFF),
    unfocusedContainerColor = Color(0x0DFFFFFF),
    focusedBorderColor = accent,
    unfocusedBorderColor = Color(0x66FFFFFF),
    cursorColor = accent,
    focusedLabelColor = accent,
    unfocusedLabelColor = Color(0xFFCBD5E1),
    focusedPlaceholderColor = Color(0x80FFFFFF),
    unfocusedPlaceholderColor = Color(0x80FFFFFF),
)

/** Keyboard "Next" on every field, "Done" on the last one. */
fun tvKeyboardOptions(last: Boolean, type: KeyboardType = KeyboardType.Text) =
    KeyboardOptions(keyboardType = type, imeAction = if (last) ImeAction.Done else ImeAction.Next)

/** Next -> the field/button below; Done -> close the keyboard and go to the button below. */
@Composable
fun tvKeyboardActions(): KeyboardActions {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return KeyboardActions(
        onNext = { focus.moveFocus(FocusDirection.Down) },
        onDone = { keyboard?.hide(); focus.moveFocus(FocusDirection.Down) },
    )
}

/** D-pad up/down leave the field instead of being swallowed by it. */
@Composable
fun Modifier.tvFieldNav(): Modifier {
    val focus = LocalFocusManager.current
    return this.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionDown -> focus.moveFocus(FocusDirection.Down)
            Key.DirectionUp -> focus.moveFocus(FocusDirection.Up)
            else -> false
        }
    }
}
