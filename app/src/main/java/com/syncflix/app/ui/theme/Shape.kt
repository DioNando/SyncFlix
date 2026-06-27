package com.syncflix.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Échelle de formes SyncFlix, alignée sur Material 3 Expressive (coins généreux) et sur la charte
 * graphique commune (champs 16.dp, cartes 16–20.dp).
 *
 * Passée à [androidx.compose.material3.MaterialExpressiveTheme] : tous les composants Material
 * (Card, Button, Chip, Dialog, BottomSheet…) l'adoptent automatiquement.
 */
val SyncFlixShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),   // champs (= SyncFlixFieldShape) et cartes standard
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
