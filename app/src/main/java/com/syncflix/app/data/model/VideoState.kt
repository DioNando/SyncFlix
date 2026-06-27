package com.syncflix.app.data.model

/**
 * État de lecture reçu en temps réel sur le canal (événement `VideoStateUpdated`).
 *
 * Reflète le payload diffusé par le serveur (cf. ARCHITECTURE.md). [triggeredBy] identifie le
 * téléphone à l'origine du changement → permet d'ignorer son propre événement (anti-boucle).
 */
data class VideoState(
    val isPlaying: Boolean,
    val positionMs: Long,
    val seq: Long,
    val serverTimestampMs: Long,
    val triggeredBy: String?,
)
