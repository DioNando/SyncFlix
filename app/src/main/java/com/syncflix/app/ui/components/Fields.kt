package com.syncflix.app.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
)
