package com.syncflix.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Thème sombre « salle obscure » — l'usage par défaut de SyncFlix (visionnage le soir).
// Accent = bleu ÉCLAIRCI (le bleu de marque #004071 sert de primaryContainer, illisible en accent
// sur fond sombre) ; or pâle en secondaire (ressort sur le sombre).
private val DarkColors = darkColorScheme(
    primary = BlueAccent,
    onPrimary = BlueOnAccent,
    primaryContainer = BrandBlue,
    onPrimaryContainer = OnBlueContainerDark,
    secondary = Gold,
    onSecondary = OnGoldDark,
    // Conteneur secondaire = or pâle de marque (#FFF2A1) dans les DEUX thèmes, texte foncé →
    // permet au bouton « Créer » d'afficher littéralement #FFF2A1 partout.
    secondaryContainer = Gold,
    onSecondaryContainer = OnGoldDark,
    tertiary = Gold,
    background = CinemaBackground,
    onBackground = CinemaOnSurface,
    surface = CinemaSurface,
    onSurface = CinemaOnSurface,
    surfaceContainer = CinemaSurface,
    surfaceContainerHigh = CinemaSurfaceHigh,
    surfaceContainerHighest = CinemaSurfaceHighest,
)

// Thème clair de repli (si l'utilisateur force le mode clair via le système).
// Ici le bleu de marque #004071 est lisible en `primary` ; l'or pâle, trop clair en aplat, passe
// en conteneur (texte foncé), et le secondaire est un or assombri.
private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = Color.White,
    primaryContainer = BlueContainerLight,
    onPrimaryContainer = OnBlueContainerLight,
    secondary = GoldDeep,
    onSecondary = Color.White,
    secondaryContainer = Gold,
    onSecondaryContainer = OnGoldContainerLight,
    tertiary = GoldDeep,
)

/** Material You disponible uniquement à partir d'Android 12 (API 31). */
val dynamicColorSupported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * Thème de marque SyncFlix, basé sur **Material 3 Expressive**.
 *
 * On conserve la palette « cinéma sombre + bleu nuit & or pâle » (les schémas Expressive génériques
 * diluent l'identité). L'apport Expressive vient de :
 * - [MotionScheme.expressive] : ressorts physiques appliqués automatiquement à tous les composants.
 * - [SyncFlixShapes] : échelle de formes arrondie (cf. Shape.kt).
 *
 * Voir `MATERIAL3_EXPRESSIVE.md` (racine) pour la documentation complète.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SyncFlixTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && dynamicColorSupported -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = SyncFlixShapes,
        typography = Typography,
        content = content,
    )
}
