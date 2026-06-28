package com.syncflix.app.domain.sync

import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.syncflix.app.data.model.VideoState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Cœur de la synchronisation (cf. ARCHITECTURE.md). Ferme la boucle dans les deux sens :
 *
 * - **Émettre** : intercepte les Play/Pause (via [Player.Listener.onPlayWhenReadyChanged]) et les
 *   seeks (via [Player.Listener.onPositionDiscontinuity]) initiés localement, et les pousse au serveur.
 * - **Appliquer** : sur un état reçu du canal, filtre l'écho (`triggeredBy`) et le désordre (`seq`),
 *   calcule la position attendue via l'offset d'horloge, puis aligne play/pause et saute si l'écart
 *   est trop grand.
 *
 * **Anti-boucle** : appliquer un état distant modifie le player, ce qui déclenche les listeners.
 * Pour ne pas réémettre, on ouvre une **fenêtre de suppression** ([suppressEmitUntil]) autour de
 * chaque mutation programmatique — robuste même si ExoPlayer livre ses callbacks de façon différée.
 *
 * **Dérive** : un contrôle périodique resynchronise si les deux lecteurs s'éloignent entre deux actions.
 *
 * Toutes les méthodes s'appellent sur le thread principal (exigence ExoPlayer).
 */
class PlaybackSyncManager(
    private val player: ExoPlayer,
    private val clientId: String,
    private val scope: CoroutineScope,
    private val clockOffset: () -> Long,
    private val onEmit: suspend (isPlaying: Boolean, positionMs: Long) -> Unit,
    private val onRemoteApplied: (VideoState) -> Unit = {},
    // Dérive estimée (ms, signée : >0 = on est en retard sur la position attendue) — debug/diagnostic.
    private val onDrift: (Long) -> Unit = {},
) {
    private var lastAppliedSeq = -1L
    private var lastRemote: VideoState? = null

    @Volatile
    private var suppressEmitUntil = 0L
    private var syncJob: Job? = null
    private var currentSpeed = 1f

    private val listener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // Une mutation programmatique a le même `reason` (USER_REQUEST) qu'un tap UI :
            // on distingue via la fenêtre de suppression, pas via le reason.
            maybeEmit()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) maybeEmit()
        }
    }

    fun start() {
        // Absorbe l'autoplay initial et le calage d'entrée (seed) pour ne pas émettre au démarrage.
        suppressEmitUntil = SystemClock.uptimeMillis() + BOOTSTRAP_SUPPRESS_MS
        player.addListener(listener)
        // Boucle de contrôle continue : rattrape la dérive en douceur (vitesse) ou par saut.
        syncJob = scope.launch {
            while (isActive) {
                delay(SYNC_TICK_MS)
                syncTick()
            }
        }
    }

    fun stop() {
        player.removeListener(listener)
        syncJob?.cancel()
        syncJob = null
    }

    /** Cale le lecteur sur l'état initial de la session (sans retour visuel : ce n'est pas l'autre). */
    fun seed(state: VideoState) {
        lastAppliedSeq = state.seq
        lastRemote = state
        reconcile(state)
    }

    /** Applique un état reçu du canal : filtre l'écho et le désordre, puis réconcilie. */
    fun applyRemote(state: VideoState) {
        if (state.triggeredBy == clientId) {              // notre propre écho
            lastAppliedSeq = maxOf(lastAppliedSeq, state.seq)
            return
        }
        if (state.seq <= lastAppliedSeq) return           // message en retard / doublon
        lastAppliedSeq = state.seq
        lastRemote = state
        reconcile(state)
        onRemoteApplied(state)  // retour visuel : action déclenchée par l'autre personne
    }

    private fun maybeEmit() {
        if (SystemClock.uptimeMillis() < suppressEmitUntil) return  // changement d'origine distante
        val playing = player.playWhenReady
        val pos = player.currentPosition.coerceAtLeast(0)
        scope.launch { runCatching { onEmit(playing, pos) } }
    }

    private fun reconcile(state: VideoState) {
        if (player.playWhenReady != state.isPlaying) {
            suppressEmitUntil = SystemClock.uptimeMillis() + SUPPRESS_MS
            player.playWhenReady = state.isPlaying
        }
        val diff = targetPosition(state) - player.currentPosition
        when {
            // En pause : alignement à l'image près (un saut n'est pas gênant, vidéo arrêtée).
            !state.isPlaying -> if (abs(diff) > PAUSE_SEEK_MS) hardSeek(targetPosition(state))
            // En lecture : seul un gros écart (seek volontaire) justifie un saut ; le reste est
            // rattrapé en douceur par la boucle de contrôle (correction de vitesse).
            abs(diff) > BIG_SEEK_MS -> hardSeek(targetPosition(state))
        }
    }

    /**
     * Boucle de contrôle (≈ toutes les [SYNC_TICK_MS] ms) : maintient l'alignement en lecture.
     * - écart < [DEADBAND_MS] → vitesse normale (synchronisé) ;
     * - écart modéré → **correction douce de la vitesse** (proportionnelle, plafonnée), pour rattraper
     *   ou ralentir imperceptiblement ;
     * - écart > [BIG_SEEK_MS] → saut franc (rattrapage impossible en douceur).
     */
    private fun syncTick() {
        val state = lastRemote
        if (state == null || !state.isPlaying || !player.playWhenReady) {
            setSpeed(1f)  // en pause / pas d'état distant : pas de correction
            onDrift(0L)
            return
        }
        val diff = targetPosition(state) - player.currentPosition  // >0 : on est en retard
        onDrift(diff)
        val gap = abs(diff)
        when {
            gap > BIG_SEEK_MS -> { setSpeed(1f); hardSeek(targetPosition(state)) }
            gap < DEADBAND_MS -> setSpeed(1f)
            else -> {
                // En retard → on accélère (>1) ; en avance → on ralentit (<1). Pente plafonnée.
                val correction = (diff.toFloat() / CATCHUP_MS).coerceIn(-MAX_RATE, MAX_RATE)
                setSpeed(1f + correction)
            }
        }
    }

    private fun hardSeek(target: Long) {
        suppressEmitUntil = SystemClock.uptimeMillis() + SUPPRESS_MS
        player.seekTo(target)
    }

    /** Applique une vitesse de lecture (no-op si déjà proche) — un changement de vitesse n'émet rien. */
    private fun setSpeed(speed: Float) {
        if (abs(speed - currentSpeed) < 0.005f) return
        currentSpeed = speed
        player.setPlaybackSpeed(speed)
    }

    /** Position attendue maintenant, extrapolée de l'horodatage serveur + l'offset d'horloge. */
    private fun targetPosition(state: VideoState): Long {
        if (!state.isPlaying) return state.positionMs
        val serverNow = System.currentTimeMillis() + clockOffset()
        val elapsed = serverNow - state.serverTimestampMs
        return (state.positionMs + elapsed).coerceAtLeast(0)
    }

    companion object {
        private const val PAUSE_SEEK_MS = 150L      // pause : alignement quasi exact (même image)
        private const val DEADBAND_MS = 120L        // en deçà : considéré synchronisé (vitesse normale)
        private const val BIG_SEEK_MS = 1500L       // au-delà : saut franc (rattrapage en vitesse trop long)
        private const val MAX_RATE = 0.08f          // correction de vitesse plafonnée à ±8 %
        private const val CATCHUP_MS = 6000f        // gain : ferme un écart d'1 s en ~6 s (avant plafond)
        private const val SYNC_TICK_MS = 500L       // cadence de la boucle de contrôle
        private const val SUPPRESS_MS = 800L        // fenêtre anti-boucle après une mutation
        private const val BOOTSTRAP_SUPPRESS_MS = 1500L // anti-émission au démarrage de l'écran
    }
}
