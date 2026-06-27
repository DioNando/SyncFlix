package com.syncflix.app.ui.theme

import androidx.compose.ui.graphics.Color

// Charte de marque SyncFlix : « cinéma sombre + bleu nuit & or pâle ».
// Couleurs de marque : primary #004071 (bleu profond), secondary #FFF2A1 (or pâle).
//
// ⚠️ #004071 est trop sombre pour servir d'accent DIRECT sur un fond quasi-noir (illisible). On le
// garde comme ancre de marque : en thème clair c'est `primary` ; en thème sombre c'est
// `primaryContainer`, et l'accent visible (`primary`) est un bleu ÉCLAIRCI du même ton. L'or pâle
// ressort parfaitement sur fond sombre → secondaire/accent chaud.

val BrandBlue = Color(0xFF004071)        // couleur de marque (primary en thème clair)
val BlueAccent = Color(0xFF5AA0DA)       // bleu éclairci, lisible comme accent sur fond sombre
val BlueOnAccent = Color(0xFF00263F)     // encre sur l'accent clair (thème sombre)
val BlueContainerLight = Color(0xFFCFE6FF) // conteneur bleu pâle (thème clair)
val OnBlueContainerLight = Color(0xFF001D33)
val OnBlueContainerDark = Color(0xFFCFE6FF)

val Gold = Color(0xFFFFF2A1)             // or pâle (secondaire / accent chaud sur fond sombre)
val GoldDeep = Color(0xFF6B5E00)         // or assombri, lisible en secondaire sur thème clair
val OnGoldDark = Color(0xFF3A3500)       // encre sur l'or pâle (thème sombre)
val OnGoldContainerLight = Color(0xFF201C00)

// Fonds « salle obscure » : noir teinté bleu nuit, surfaces progressives.
val CinemaBackground = Color(0xFF0A1118)
val CinemaSurface = Color(0xFF0F1822)
val CinemaSurfaceHigh = Color(0xFF16212E)
val CinemaSurfaceHighest = Color(0xFF1D2A39)
val CinemaOnSurface = Color(0xFFE3E9F0)  // blanc cassé légèrement froid
