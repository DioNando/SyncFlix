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
    val streamPath: String = "",
    val subtitles: List<Subtitle> = emptyList(),
    val isPlaying: Boolean,
    val positionMs: Long,
    val seq: Long,
    val serverTimestampMs: Long,
) {
    /**
     * URL de streaming du film. Par défaut servie **directement par Caddy** (`/media/...`,
     * multi-thread + Range natif) pour ne pas saturer le `php artisan serve` mono-thread ; repli
     * sur la route PHP `/api/movies/{id}/stream` si le serveur n'expose pas de `streamPath`.
     */
    fun streamUrl(): String {
        val base = serverUrl.trimEnd('/')
        return if (streamPath.isNotEmpty()) "$base/media/$streamPath" else "$base/api/movies/$movieId/stream"
    }

    /** URL d'une piste de sous-titres sidecar (servie par le backend, type MIME porté par [Subtitle]). */
    fun subtitleUrl(index: Int): String =
        "${serverUrl.trimEnd('/')}/api/movies/$movieId/subtitles/$index"
}
