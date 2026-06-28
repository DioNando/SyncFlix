package com.syncflix.app.data.model

/**
 * Résultat de recherche TMDB (proxifié par le backend `/api/search`).
 *
 * [posterPath] est le chemin TMDB brut (`/abc.jpg`) renvoyé pour pouvoir réajouter le film en
 * watchlist ; [posterUrl] est l'URL complète prête à afficher.
 */
data class TmdbMovie(
    val tmdbId: Long,
    val title: String,
    val year: String?,
    val posterPath: String?,
    val posterUrl: String?,
    val overview: String?,
)

/** Un film de la watchlist (`/api/wishlist`). [available] = un fichier correspondant existe en bibliothèque. */
data class WishlistItem(
    val id: Long,
    val tmdbId: Long,
    val title: String,
    val year: String?,
    val posterUrl: String?,
    val overview: String?,
    val watched: Boolean,
    val available: Boolean,
)
