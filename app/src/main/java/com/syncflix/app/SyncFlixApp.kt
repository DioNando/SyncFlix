package com.syncflix.app

import android.app.Application
import com.syncflix.app.data.settings.SettingsStore

/**
 * Conteneur de dépendances de l'application (pas de framework DI).
 *
 * Initialise les préférences persistées ([SettingsStore]) au démarrage. Les services réseau restent
 * créés paresseusement par écran (cf. ARCHITECTURE.md).
 */
class SyncFlixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
    }
}
