package com.syncflix.app.data.model

/**
 * Un film de la bibliothèque serveur (`GET /api/movies`), à choisir dans l'app.
 *
 * [subtitles] = pistes sidecar découvertes au scan. La sélection audio/sous-titres est **locale**
 * (sélecteur Media3), non synchronisée entre les deux spectateurs (cf. ARCHITECTURE.md).
 */
data class Movie(
    val id: Long,
    val title: String,
    // Segment de fichier servi par Caddy (/media/{streamPath}), déjà URL-encodé. Vide → fallback PHP.
    val streamPath: String = "",
    val subtitles: List<Subtitle> = emptyList(),
)

/** Une piste de sous-titres d'un film. [index] = position côté serveur (sert à construire l'URL). */
data class Subtitle(
    val index: Int,
    val lang: String,
    val label: String,
    val mime: String,
)
