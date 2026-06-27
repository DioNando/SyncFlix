package com.syncflix.app

import android.app.Application

/**
 * Conteneur de dépendances de l'application (pas de framework DI).
 *
 * Vide pour l'instant (étape 2 du MVP — lecteur seul). Les services réseau (client HTTP partagé,
 * WebSocket Reverb, `ClockSync`) y seront créés paresseusement et partagés aux étapes 3-4,
 * comme dans WorkSync (cf. ARCHITECTURE.md).
 */
class SyncFlixApp : Application()
