package com.syncflix.app.ui.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.syncflix.app.R
import com.syncflix.app.data.model.SessionState
import com.syncflix.app.data.model.VideoState
import com.syncflix.app.data.remote.ClockSync
import com.syncflix.app.data.remote.SessionApi
import com.syncflix.app.data.remote.SyncSocket
import com.syncflix.app.domain.sync.PlaybackSyncManager
import kotlinx.coroutines.launch
import java.util.UUID

// Clé d'app Reverb (publique) — doit correspondre à REVERB_APP_KEY du .env serveur.
private const val REVERB_APP_KEY = "9dfaadf61b6ac53c4a66"

// --- Validation étape 2 (ExoPlayer), en attendant le backend (étape 1) ---------------------------
// Flux MP4 public (W3C) — utilisé seulement si [USE_TEST_STREAM] = true (validation hors backend).
// Backend en place (étape 1) → on lit désormais le vrai flux Laravel `session.streamUrl(movieId)`.
// 🔧 Remettre à `true` pour retester sans serveur/tunnel.
private const val USE_TEST_STREAM = false
private const val TEST_STREAM_URL =
    "https://media.w3.org/2010/05/sintel/trailer.mp4"

/** État de la connexion temps réel, affiché discrètement dans le bandeau du lecteur. */
private enum class SyncStatus { Connecting, Synced, Offline }

/** Convertit l'état de session (REST) en état de lecture (pour caler/resynchroniser le lecteur). */
private fun SessionState.asVideoState() = VideoState(
    isPlaying = isPlaying,
    positionMs = positionMs,
    seq = seq,
    serverTimestampMs = serverTimestampMs,
    triggeredBy = null,
)

/**
 * Écran de lecture vidéo, synchronisé entre les deux téléphones.
 *
 * - **Lecture** : ExoPlayer via [AndroidView], flux HTTP `Range` du serveur Laravel ; contrôles natifs
 *   de [PlayerView] (play/pause/seek), plus overlays de chargement et d'erreur.
 * - **Synchro (étape 4)** : un [PlaybackSyncManager] **intercepte** les actions locales (via les
 *   listeners ExoPlayer) pour les pousser au serveur, et **applique** l'état reçu sur le canal WS
 *   (offset d'horloge [ClockSync], anti-boucle par `triggered_by`, correction de dérive). On garde
 *   les contrôles natifs : l'interception par listener suffit, pas besoin d'un overlay maison.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    session: SessionState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // URL lue : flux de test tant que le backend n'est pas debout, sinon le flux de la session.
    val mediaUrl = if (USE_TEST_STREAM) TEST_STREAM_URL else session.streamUrl()

    var buffering by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // --- Synchro temps réel (étapes 3-4) ----------------------------------------------------------
    val scope = rememberCoroutineScope()
    val sessionApi = remember { SessionApi() }
    var status by remember { mutableStateOf(SyncStatus.Connecting) }
    // Action distante récente (l'autre a joué/mis en pause) : affichée brièvement puis effacée.
    var remoteHint by remember { mutableStateOf<VideoState?>(null) }
    // Identité de ce téléphone pour la session : sert à ignorer son propre écho (anti-boucle).
    val clientId = remember { UUID.randomUUID().toString() }
    // Offset d'horloge mobile↔serveur, mesuré en HTTP au démarrage (0 en attendant).
    var clockOffset by remember { mutableStateOf(0L) }

    // ExoPlayer lié à la composition : créé une fois, libéré à la sortie de l'écran.
    val exoPlayer = remember {
        // Source HTTP : on envoie `ngrok-skip-browser-warning` pour sauter la page d'avertissement
        // du plan gratuit ngrok (sinon le tunnel renvoie du HTML au lieu de la vidéo). Header inerte
        // pour un autre tunnel / le serveur direct, donc sans risque de le laisser en permanence.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("ngrok-skip-browser-warning" to "true"))
        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                setMediaItem(MediaItem.fromUri(mediaUrl))
                // Démarre dans l'état partagé de la session ; le seed cale ensuite la position.
                playWhenReady = USE_TEST_STREAM || session.isPlaying
                prepare()
            }
    }

    // Écoute l'état de lecture (mémoire tampon) + les erreurs, pour les remonter à l'overlay.
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onPlayerError(error: PlaybackException) {
                errorText = "${error.errorCodeName}\n${error.message ?: ""}"
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    // Met en pause quand l'app passe en arrière-plan ; libère les ressources à la destruction.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> exoPlayer.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
        }
    }

    // Gestionnaire de synchro : émet les Play/Pause/Seek locaux, applique l'état distant (étape 4).
    val syncManager = remember(exoPlayer) {
        PlaybackSyncManager(
            player = exoPlayer,
            clientId = clientId,
            scope = scope,
            clockOffset = { clockOffset },
            onEmit = { playing, pos ->
                sessionApi.updateState(session.serverUrl, session.code, playing, pos, clientId)
            },
            onRemoteApplied = { remoteHint = it },
        )
    }
    // Efface le retour visuel d'action distante après un court instant.
    LaunchedEffect(remoteHint) {
        if (remoteHint != null) {
            kotlinx.coroutines.delay(2500)
            remoteHint = null
        }
    }
    DisposableEffect(syncManager) {
        syncManager.start()
        onDispose { syncManager.stop() }
    }

    // Cale immédiatement le lecteur sur l'état initial de la session (au join/create).
    LaunchedEffect(syncManager) {
        syncManager.seed(
            VideoState(
                isPlaying = session.isPlaying,
                positionMs = session.positionMs,
                seq = session.seq,
                serverTimestampMs = session.serverTimestampMs,
                triggeredBy = null,
            ),
        )
    }
    // Mesure l'offset d'horloge mobile↔serveur (affine la synchro ; la dérive corrige le reste).
    LaunchedEffect(session.serverUrl) {
        clockOffset = runCatching { ClockSync().measureOffset(session.serverUrl) }.getOrDefault(0L)
    }

    // Connexion WebSocket au canal de la session (protocole Pusher/Reverb). L'URL WS est dérivée de
    // l'URL serveur (https→wss), même hôte grâce au reverse proxy Caddy. Fermée à la sortie.
    DisposableEffect(session.code) {
        val wsBase = session.serverUrl.trim().trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val socket = SyncSocket(
            wsUrl = "$wsBase/app/$REVERB_APP_KEY?protocol=7&client=android&version=1.0",
            channel = "movie-session.${session.code}",
            scope = scope,
        )
        socket.connect { event ->
            // Les callbacks OkHttp arrivent hors du thread UI → on repasse sur le scope (Main).
            scope.launch {
                when (event) {
                    SyncSocket.Event.Connected -> status = SyncStatus.Connecting
                    SyncSocket.Event.Subscribed -> {
                        status = SyncStatus.Synced
                        // Resync : l'état a pu changer avant l'abonnement (entrée ou reconnexion).
                        runCatching {
                            syncManager.applyRemote(
                                sessionApi.getState(session.serverUrl, session.code).asVideoState(),
                            )
                        }
                    }
                    is SyncSocket.Event.State -> syncManager.applyRemote(event.state)
                    SyncSocket.Event.Disconnected -> status = SyncStatus.Offline
                }
            }
        }
        onDispose { socket.close() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    keepScreenOn = true
                    setShowNextButton(false)
                    setShowPreviousButton(false)
                }
            },
        )

        // Overlay de chargement (spinner) — visible tant que le flux n'est pas prêt, sans erreur.
        if (buffering && errorText == null) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.player_buffering),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            }
        }

        // Overlay d'erreur — affiche le code ExoPlayer + le message (diagnostic).
        errorText?.let { message ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "⚠️",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = mediaUrl,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Bandeau supérieur : retour + code de session + état de synchro (translucide).
        Surface(
            color = Color.Black.copy(alpha = 0.35f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = Color.White,
                    )
                }
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    Text(
                        text = session.movieTitle,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Text(
                        text = stringResource(R.string.player_session_code, session.code),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.weight(1f))
                // État de connexion temps réel.
                val (statusRes, statusColor) = when (status) {
                    SyncStatus.Synced -> R.string.player_synced to MaterialTheme.colorScheme.primary
                    SyncStatus.Connecting -> R.string.player_connecting to Color.White.copy(alpha = 0.7f)
                    SyncStatus.Offline -> R.string.player_offline to MaterialTheme.colorScheme.error
                }
                Text(
                    text = stringResource(statusRes),
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                )
            }
        }

        // Retour visuel transitoire : action déclenchée par l'autre personne (play/pause).
        remoteHint?.let { hint ->
            Surface(
                color = Color.Black.copy(alpha = 0.55f),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (hint.isPlaying) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(
                            if (hint.isPlaying) R.string.player_remote_play else R.string.player_remote_pause,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                }
            }
        }
    }
}
