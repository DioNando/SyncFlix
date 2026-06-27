package com.syncflix.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.syncflix.app.data.model.SessionState
import com.syncflix.app.ui.pairing.PairingScreen
import com.syncflix.app.ui.player.PlayerScreen

private object Routes {
    const val PAIRING = "pairing"
    const val PLAYER = "player"
}

/**
 * Graphe de navigation minimal : appairage → lecteur.
 *
 * Le [SessionState] actif est hissé ici (plutôt que sérialisé en argument de route) : il contient
 * des objets riches, et la session est de toute façon unique à l'écran.
 */
@Composable
fun SyncFlixNavHost() {
    val navController = rememberNavController()
    var session by remember { mutableStateOf<SessionState?>(null) }

    NavHost(navController = navController, startDestination = Routes.PAIRING) {
        composable(Routes.PAIRING) {
            PairingScreen(
                onConnect = {
                    session = it
                    navController.navigate(Routes.PLAYER)
                },
            )
        }
        composable(Routes.PLAYER) {
            val active = session
            if (active == null) {
                // Garde-fou : revenir à l'appairage si on atteint le lecteur sans session.
                navController.popBackStack(Routes.PAIRING, inclusive = false)
            } else {
                PlayerScreen(
                    session = active,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}
