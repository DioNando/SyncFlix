package com.syncflix.app.ui.player

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
// 🔧 Remettre à `true` pour retester sans serveur/tunnel (désactive aussi le salon d'attente).
private const val USE_TEST_STREAM = false
private const val TEST_STREAM_URL =
    "https://media.w3.org/2010/05/sintel/trailer.mp4"

// Palette de réactions emoji disponibles pendant le visionnage.
private val REACTIONS = listOf("❤️", "😂", "😮", "👍", "🔥")

/** État de la connexion temps réel, affiché discrètement dans le bandeau du lecteur. */
private enum class SyncStatus { Connecting, Synced, Offline }

/** Une réaction qui « flotte » à l'écran (identifiant unique pour l'animer/retirer). */
private data class FloatingReaction(val id: Long, val emoji: String)

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
 *   de [PlayerView] (play/pause/seek) + **sélection locale** des pistes audio / sous-titres (bouton CC
 *   et menu réglages de Media3 ; choix non synchronisé entre spectateurs).
 * - **Synchro** : un [PlaybackSyncManager] intercepte les actions locales et applique l'état distant.
 * - **Salon d'attente** : tant qu'un seul spectateur est connecté (présence Reverb), un voile affiche
 *   le code à partager ; il s'efface dès que l'autre arrive.
 * - **Réactions** : barre d'emojis ; chaque réaction diffusée « flotte » brièvement à l'écran.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    session: SessionState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current

    // Plein écran « cinéma » en paysage : on masque les barres système (et le bandeau, plus bas).
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    DisposableEffect(isLandscape) {
        val window = context.findActivity()?.window
        val bars = WindowInsetsCompat.Type.systemBars()
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (isLandscape) {
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(bars)
        } else {
            controller?.show(bars)
        }
        // Restaure les barres en quittant le lecteur (retour à l'appairage en portrait).
        onDispose { controller?.show(bars) }
    }

    // URL lue : flux de test tant que le backend n'est pas debout, sinon le flux de la session.
    val mediaUrl = if (USE_TEST_STREAM) TEST_STREAM_URL else session.streamUrl()

    var buffering by remember { mutableStateOf(true) }
    var errorText by remember { mutableStateOf<String?>(null) }

    // --- Synchro temps réel ------------------------------------------------------------------------
    val scope = rememberCoroutineScope()
    val sessionApi = remember { SessionApi() }
    var status by remember { mutableStateOf(SyncStatus.Connecting) }
    // Action distante récente (l'autre a joué/mis en pause) : affichée brièvement puis effacée.
    var remoteHint by remember { mutableStateOf<VideoState?>(null) }
    // Identité de ce téléphone : anti-boucle (triggered_by) ET id de membre de présence.
    val clientId = remember { UUID.randomUUID().toString() }
    // Offset d'horloge mobile↔serveur, mesuré en HTTP au démarrage (0 en attendant).
    var clockOffset by remember { mutableStateOf(0L) }

    // --- Salon d'attente + réactions ---------------------------------------------------------------
    var peers by remember { mutableStateOf(1) }           // spectateurs connectés (présence)
    var lobbyBypassed by remember { mutableStateOf(false) } // « entrer seul·e » (test/solo)
    // Verrou : une fois les deux réunis, on ne re-voile JAMAIS (sinon le départ/reconnexion du
    // partenaire recouvrirait la vidéo en pleine lecture). Le salon ne gate qu'au démarrage.
    var startedTogether by remember { mutableStateOf(false) }
    val showLobby = !USE_TEST_STREAM && !startedTogether && !lobbyBypassed && peers < 2
    val reactions = remember { mutableStateListOf<FloatingReaction>() }
    var reactionSeq by remember { mutableStateOf(0L) }

    // ExoPlayer lié à la composition : créé une fois, libéré à la sortie de l'écran.
    val exoPlayer = remember {
        // Source HTTP : on envoie `ngrok-skip-browser-warning` pour sauter la page d'avertissement
        // du plan gratuit ngrok (sinon le tunnel renvoie du HTML). Header inerte ailleurs, donc sûr.
        // S'applique aussi au téléchargement des sous-titres sidecar (même fabrique).
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(mapOf("ngrok-skip-browser-warning" to "true"))
        val mediaSourceFactory = DefaultMediaSourceFactory(httpFactory)

        // Pistes de sous-titres « sidecar » exposées par le serveur (sélection locale via le bouton CC).
        val subtitleConfigs = if (USE_TEST_STREAM) emptyList() else session.subtitles.map { sub ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(session.subtitleUrl(sub.index)))
                .setMimeType(sub.mime)
                .setLanguage(sub.lang)
                .setLabel(sub.label)
                .build()
        }
        val mediaItem = MediaItem.Builder()
            .setUri(mediaUrl)
            .setSubtitleConfigurations(subtitleConfigs)
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().apply {
                setMediaItem(mediaItem)
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

    // Gestionnaire de synchro : émet les Play/Pause/Seek locaux, applique l'état distant.
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

    // Connexion WebSocket au canal de présence de la session (protocole Pusher/Reverb). L'URL WS est
    // dérivée de l'URL serveur (https→wss), même hôte grâce au reverse proxy Caddy. Fermée à la sortie.
    DisposableEffect(session.code) {
        val wsBase = session.serverUrl.trim().trimEnd('/')
            .replaceFirst("https://", "wss://")
            .replaceFirst("http://", "ws://")
        val socket = SyncSocket(
            wsUrl = "$wsBase/app/$REVERB_APP_KEY?protocol=7&client=android&version=1.0",
            channel = "presence-movie-session.${session.code}",
            scope = scope,
            // Signature + channel_data délivrés par le serveur (le secret Reverb n'est jamais dans l'app).
            authorize = { socketId, channel ->
                sessionApi.authChannel(session.serverUrl, socketId, channel, clientId)
            },
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
                    is SyncSocket.Event.Presence -> {
                        peers = event.count
                        if (event.count >= 2) startedTogether = true
                    }
                    is SyncSocket.Event.Reaction -> reactions.add(
                        FloatingReaction(reactionSeq++, event.emoji),
                    )
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
                    setShowSubtitleButton(true) // bouton CC : choix local des sous-titres
                }
            },
            update = { view ->
                // Pendant le salon d'attente, on coupe les contrôles natifs : sinon ils
                // transparaissent sous le voile et restent cliquables (on pouvait lancer la vidéo).
                view.useController = !showLobby
                if (showLobby) view.hideController()
            },
        )

        // Overlay de chargement (spinner) — visible tant que le flux n'est pas prêt, sans erreur.
        if (buffering && errorText == null && !showLobby) {
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
                Text(text = "⚠️", style = MaterialTheme.typography.headlineMedium)
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

        // Réactions qui flottent (au-dessus de la vidéo, sous le bandeau). `key` par id → chaque
        // animation reste stable même si une autre réaction se termine au milieu de la liste.
        reactions.forEach { reaction ->
            key(reaction.id) {
                FloatingEmoji(
                    reaction = reaction,
                    onDone = { reactions.remove(reaction) },
                )
            }
        }

        // Barre de réactions (cachée pendant le salon d'attente).
        if (!showLobby) {
            ReactionBar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 88.dp),
                onReact = { emoji ->
                    scope.launch { runCatching { sessionApi.react(session.serverUrl, session.code, emoji) } }
                },
            )
        }

        // Bandeau supérieur : retour + titre + code + état de synchro (translucide).
        // Masqué en paysage pour un plein écran « cinéma » (retour via le geste système).
        if (!isLandscape) Surface(
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
                // Pastille d'état (la 1re version affichait « Synchronisé » qui se cassait en
                // vertical dans le bandeau étroit) : vert = synchro, ambre = connexion, rouge = hors ligne.
                SyncStatusDot(status)
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

        // Salon d'attente : voile bloquant tant que l'autre n'est pas connecté.
        if (showLobby) {
            LobbyOverlay(
                code = session.code,
                movieTitle = session.movieTitle,
                peers = peers,
                onCopy = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Miray", session.code))
                },
                onEnterAlone = { lobbyBypassed = true },
            )
        }
    }
}

/** Pastille d'état de synchro (couleur sémantique + description a11y), compacte pour le bandeau. */
@Composable
private fun SyncStatusDot(status: SyncStatus) {
    val color = when (status) {
        SyncStatus.Synced -> Color(0xFF34C759)                 // vert
        SyncStatus.Connecting -> Color(0xFFFFB020)             // ambre
        SyncStatus.Offline -> MaterialTheme.colorScheme.error  // rouge
    }
    val desc = stringResource(
        when (status) {
            SyncStatus.Synced -> R.string.player_synced
            SyncStatus.Connecting -> R.string.player_connecting
            SyncStatus.Offline -> R.string.player_offline
        },
    )
    Box(
        modifier = Modifier
            .padding(end = 10.dp)
            .size(12.dp)
            .clip(CircleShape)
            .background(color)
            .semantics { contentDescription = desc },
    )
}

/** Voile du salon d'attente : film, code à partager (carte « ticket ») + attente du partenaire. */
@Composable
private fun BoxScope.LobbyOverlay(
    code: String,
    movieTitle: String,
    peers: Int,
    onCopy: () -> Unit,
    onEnterAlone: () -> Unit,
) {
    var copied by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .matchParentSize()
            // Voile OPAQUE + capture des taps (sans ripple) : rien ne transparaît et les contrôles
            // du lecteur dessous ne sont pas atteignables tant que le partenaire n'est pas là.
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {}
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Pastille d'icône tonale (style charte).
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(64.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.lobby_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = movieTitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.lobby_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))

        // Carte « ticket » : le code à partager, bien lisible et détouré.
        Surface(
            shape = MaterialTheme.shapes.large,
            color = Color.White.copy(alpha = 0.06f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
        ) {
            Text(
                text = code,
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = { onCopy(); copied = true }) {
            Icon(
                imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(stringResource(if (copied) R.string.pairing_copied else R.string.pairing_copy))
        }

        Spacer(Modifier.height(40.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = stringResource(R.string.lobby_waiting, peers),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onEnterAlone) {
            Text(
                text = stringResource(R.string.lobby_enter_alone),
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

/** Barre d'emojis de réaction (diffusés à tous les spectateurs). */
@Composable
private fun ReactionBar(
    modifier: Modifier = Modifier,
    onReact: (String) -> Unit,
) {
    // Pastille translucide détourée : reste visible aussi bien sur le noir que sur l'image.
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            REACTIONS.forEach { emoji ->
                Text(
                    text = emoji,
                    fontSize = 26.sp,
                    modifier = Modifier
                        .clickable(
                            onClickLabel = stringResource(R.string.cd_react, emoji),
                            onClick = { onReact(emoji) },
                        )
                        .padding(8.dp),
                )
            }
        }
    }
}

/**
 * Un emoji qui monte en fondu puis disparaît (≈2,2 s), à droite de l'écran.
 *
 * Composable autonome occupant tout l'espace (sa propre [Box]) : empilable et `key`-able sans
 * dépendre du [BoxScope] parent.
 */
@Composable
private fun FloatingEmoji(
    reaction: FloatingReaction,
    onDone: () -> Unit,
) {
    val progress = remember(reaction.id) { Animatable(0f) }
    LaunchedEffect(reaction.id) {
        progress.animateTo(1f, animationSpec = tween(durationMillis = 2200, easing = LinearEasing))
        onDone()
    }
    val p = progress.value
    // Léger éventail horizontal déterministe selon l'id (pas de RNG → cohérent en recomposition).
    val drift = ((reaction.id % 5L).toInt() - 2) * 16
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = reaction.emoji,
            fontSize = 40.sp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 24.dp, bottom = 140.dp)
                .offset(x = drift.dp, y = (-220 * p).dp)
                .alpha(1f - p),
        )
    }
}

/** Remonte la chaîne de [Context] jusqu'à l'[Activity] (gère les `ContextWrapper` de thème). */
private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
