package com.syncflix.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Forme arrondie commune à tous les champs (saisie + sélecteurs) pour un rendu homogène. */
val SyncFlixFieldShape = RoundedCornerShape(16.dp)

/**
 * Couleurs « tonales » communes aux champs : conteneur rempli (`surfaceContainerHighest`) et
 * **aucune ligne d'indicateur** (style sans bordure de la charte). À utiliser avec un
 * `TextField` (rempli) et [SyncFlixFieldShape] pour harmoniser tous les inputs.
 */
@Composable
fun syncflixFieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    errorContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    disabledIndicatorColor = Color.Transparent,
    errorIndicatorColor = Color.Transparent,
    // Repères colorés discrets au focus (la charte reste « sans bordure »).
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
)

/**
 * Champ de **code segmenté** : une case par caractère (le code d'appairage fait toujours 6 signes).
 *
 * Implémentation « OTP » : un [BasicTextField] invisible capte la saisie (filtrée en MAJUSCULES,
 * lettres+chiffres, longueur bornée) ; le rendu est dessiné main — [length] cases tonales, la case à
 * remplir surlignée en `primary` quand le champ a le focus.
 */
@Composable
fun CodeField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    length: Int = 6,
    enabled: Boolean = true,
    isError: Boolean = false,
    contentDescription: String = "",
) {
    var focused by remember { mutableStateOf(false) }

    BasicTextField(
        value = value,
        onValueChange = { raw ->
            onValueChange(raw.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' }.take(length))
        },
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .semantics { if (contentDescription.isNotEmpty()) this.contentDescription = contentDescription },
        enabled = enabled,
        singleLine = true,
        // Le vrai texte est masqué (transparent + curseur transparent) : seules les cases sont visibles.
        textStyle = TextStyle(color = Color.Transparent),
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
        decorationBox = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                repeat(length) { i ->
                    val char = if (i < value.length) value[i].toString() else ""
                    val active = focused && i == value.length.coerceAtMost(length - 1)
                    val border = when {
                        isError -> BorderStroke(2.dp, MaterialTheme.colorScheme.error)
                        active -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        else -> null
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .clip(SyncFlixFieldShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .then(if (border != null) Modifier.border(border, SyncFlixFieldShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Text(
                            text = char,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        },
    )
}
