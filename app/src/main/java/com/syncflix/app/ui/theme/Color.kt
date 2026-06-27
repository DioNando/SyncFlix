package com.syncflix.app.ui.theme

import androidx.compose.ui.graphics.Color

// Charte de marque SyncFlix : « cinéma sombre + corail ».
// Corail chaud en accent, violet doux en secondaire, fonds proches du noir teinté.
val Coral = Color(0xFFFF5A5F)        // accent primaire (boutons, play, marque)
val CoralDeep = Color(0xFFE04146)    // corail assombri (états pressés / variantes)
val CoralSoft = Color(0xFFFFD9DA)    // corail très clair (conteneurs en thème clair)
val VioletSoft = Color(0xFFB388FF)   // secondaire (chips, accents discrets)

// Fonds « salle obscure » : noir légèrement violacé, surfaces progressives.
val CinemaBackground = Color(0xFF0E0B10)
val CinemaSurface = Color(0xFF16121A)
val CinemaSurfaceHigh = Color(0xFF211B28)
val CinemaSurfaceHighest = Color(0xFF2B2433)

// Thème sombre (usage principal) : accents clairs sur fonds obscurs.
val OnCoralDark = Color(0xFF3A090B)
val CoralContainerDark = Color(0xFF5A1F22)
val OnCoralContainerDark = Color(0xFFFFD9DA)
val OnVioletDark = Color(0xFF2A1A4D)

// Thème clair (repli si l'utilisateur force le mode clair) : encre profonde sur surfaces pâles.
val OnCoralContainerLight = Color(0xFF410006)
val VioletInk = Color(0xFF5A3CA8)
