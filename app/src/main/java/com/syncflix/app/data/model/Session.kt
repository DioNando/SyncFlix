package com.syncflix.app.data.model

/**
 * État d'une session de visionnage, tel que renvoyé par le backend (`/api/sessions/...`).
 *
 * Reflète le contrat de l'état partagé autoritatif (cf. ARCHITECTURE.md). [serverUrl] n'est pas
 * dans la réponse JSON : on le réinjecte côté client pour pouvoir reconstruire les URL (streaming).
 */
data class SessionState(
    val serverUrl: String,
    val code: String,
    val movieId: Long,
    val movieTitle: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val seq: Long,
    val serverTimestampMs: Long,
) {
    /** URL de streaming du film de la session (requêtes `Range` gérées par ExoPlayer). */
    fun streamUrl(): String =
        "${serverUrl.trimEnd('/')}/api/movies/$movieId/stream"
}
