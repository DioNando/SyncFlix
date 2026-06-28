package com.syncflix.app.ui.player

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.ForwardingPlayer
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
import com.syncflix.app.data.settings.SettingsStore
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
    // ⚠️ On détecte l'orientation via la TAILLE RÉELLE de la fenêtre, pas via `Configuration`, car
    // `MainActivity.attachBaseContext` (locale forcée FR) fige la config → l'orientation n'y est pas
    // mise à jour à la rotation. La taille de fenêtre, elle, change bien.
    val windowSize = LocalWindowInfo.current.containerSize
    val isLandscape = windowSize.width > windowSize.height
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
    // Dérive de synchro estimée (ms, signée) — affichée si le mode debug est activé.
    var drift by remember { mutableStateOf(0L) }

    // --- Salon d'attente + réactions ---------------------------------------------------------------
    var peers by remember { mutableStateOf(1) }           // spectateurs connectés (présence)
    var lobbyBypassed by remember { mutableStateOf(false) } // « entrer seul·e » (test/solo)
    // Verrou : une fois les deux réunis, on ne re-voile JAMAIS (sinon le départ/reconnexion du
    // partenaire recouvrirait la vidéo en pleine lecture). Le salon ne gate qu'au démarrage.
    var startedTogether by remember { mutableStateOf(false) }
    val showLobby = !USE_TEST_STREAM && !startedTogether && !lobbyBypassed && peers < 2
    val reactions = remember { mutableStateListOf<FloatingReaction>() }
    var reactionSeq by remember { mutableStateOf(0L) }
    // Notice transitoire quand le partenaire se dé/reconnecte (true = de retour, false = parti).
    var partnerNotice by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(partnerNotice) {
        if (partnerNotice != null) {
            kotlinx.coroutines.delay(3500)
            partnerNotice = null
        }
    }

    // --- Réglages + état d'écran -------------------------------------------------------------------
    val settings = SettingsStore.settings
    var locked by remember { mutableStateOf(false) }          // verrou des contrôles (anti-touche)
    var showQuitConfirm by remember { mutableStateOf(false) } // confirmation avant de quitter
    // Visibilité de la chrome (header + barre de réactions), pilotée par le contrôleur natif
    // (ControllerVisibilityListener) : tap → affiche, auto-masquage Media3, 2e tap → masque.
    var controlsVisible by remember { mutableStateOf(true) }

    // Le retour système demande confirmation (on ne quitte pas une session par inadvertance).
    BackHandler(enabled = !showQuitConfirm) {
        if (locked) locked = false else showQuitConfirm = true
    }

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

    // Player « vitrine » pour le CONTRÔLEUR natif : masque la commande de vitesse → l'option Vitesse
    // disparaît du menu. Le vrai [exoPlayer] reste piloté par le PlaybackSyncManager (correction de
    // dérive par la vitesse), donc retirer la vitesse de l'UI n'impacte pas la synchro.
    val controllerPlayer = remember(exoPlayer) {
        object : ForwardingPlayer(exoPlayer) {
            override fun getAvailableCommands(): Player.Commands =
                super.getAvailableCommands().buildUpon()
                    .remove(Player.COMMAND_SET_SPEED_AND_PITCH)
                    .build()

            override fun isCommandAvailable(command: Int): Boolean =
                command != Player.COMMAND_SET_SPEED_AND_PITCH && super.isCommandAvailable(command)
        }
    }

    // Écoute l'état de lecture (mémoire tampon) + les erreurs, pour les remonter à l'overlay.
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
            }

            override fun onPlayerError(error: PlaybackException) {
                // Message clair selon la nature : décodage (format non supporté, ex. HEVC 10-bit)
                // vs réseau/source. Le code technique est gardé en petit pour le diagnostic.
                val friendlyRes = when (error.errorCode) {
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                        R.string.player_error_codec
                    else -> R.string.player_error_network
                }
                errorText = "${context.getString(friendlyRes)}\n(${error.errorCodeName})"
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
            onDrift = { drift = it },
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
                        val was = peers
                        peers = event.count
                        if (event.count >= 2) startedTogether = true
                        // Notice seulement après le démarrage à deux (pas au 1er abonnement).
                        if (startedTogether) {
                            if (was >= 2 && event.count < 2) partnerNotice = false       // parti
                            else if (was < 2 && event.count >= 2) partnerNotice = true    // de retour
                        }
                    }
                    is SyncSocket.Event.Reaction ->
                        // Lecture live du réglage (le lambda survit aux changements de réglage).
                        if (SettingsStore.settings.showReactions) {
                            reactions.add(FloatingReaction(reactionSeq++, event.emoji))
                        }
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
                // Inflé depuis XML pour obtenir une TEXTURE_VIEW (évite le flash noir à la sortie).
                // Contrôles NATIFS Media3 ; on lui donne un player « enveloppé » qui masque la commande
                // de vitesse → l'option Vitesse disparaît du menu, SANS impacter la synchro (le
                // PlaybackSyncManager pilote le vrai exoPlayer pour sa correction de dérive).
                (LayoutInflater.from(ctx).inflate(R.layout.view_player, null) as PlayerView).apply {
                    player = controllerPlayer
                    // Header + barre de réactions suivent la visibilité des contrôles natifs.
                    setControllerVisibilityListener(
                        PlayerView.ControllerVisibilityListener { visibility ->
                            controlsVisible = visibility == View.VISIBLE
                        },
                    )
                }
            },
            update = { view ->
                // Contrôles natifs coupés pendant le salon d'attente (sinon ils transparaissent sous
                // le voile) et quand l'écran est verrouillé (anti-touche).
                view.useController = !showLobby && !locked
                if (showLobby || locked) view.hideController()
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
        // Abaissées en paysage (sinon trop hautes) et masquables via les réglages.
        if (settings.showReactions) {
            val floatingBottom = if (isLandscape) 40.dp else 140.dp
            reactions.forEach { reaction ->
                key(reaction.id) {
                    FloatingEmoji(
                        reaction = reaction,
                        bottomPadding = floatingBottom,
                        onDone = { reactions.remove(reaction) },
                    )
                }
            }
        }

        // Barre de réactions : visible avec les contrôles natifs (tap pour afficher, auto-masquage),
        // sauf pendant le salon d'attente, le verrou, ou si désactivée. Placée au-dessus de la
        // barre de progression (plus bas en paysage).
        if (controlsVisible && !showLobby && !locked && settings.showReactions) {
            ReactionBar(
                emojis = settings.reactions,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = if (isLandscape) 56.dp else 96.dp),
                onReact = { emoji ->
                    scope.launch { runCatching { sessionApi.react(session.serverUrl, session.code, emoji) } }
                },
            )
        }

        // Bandeau supérieur : retour + titre + code + verrou + état de synchro (translucide).
        // Suit la visibilité des contrôles (sinon il couvre le haut de la vidéo en lecture) ;
        // masqué en paysage (plein écran « cinéma ») et quand l'écran est verrouillé.
        if (!isLandscape && !locked && controlsVisible) Surface(
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
                IconButton(onClick = { showQuitConfirm = true }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.cd_back),
                        tint = Color.White,
                    )
                }
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    // Titre masquable via les réglages.
                    if (settings.showMovieTitle) {
                        Text(
                            text = session.movieTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                        )
                    }
                    Text(
                        text = stringResource(R.string.player_session_code, session.code),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.weight(1f))
                // Indicateurs groupés : présence du partenaire (vert = connecté, grisé = déconnecté)
                // puis pastille d'état de synchro (vert/ambre/rouge).
                Icon(
                    imageVector = if (peers >= 2) Icons.Rounded.Person else Icons.Rounded.PersonOff,
                    contentDescription = stringResource(
                        if (peers >= 2) R.string.player_partner_connected else R.string.player_partner_disconnected,
                    ),
                    tint = if (peers >= 2) Color(0xFF34C759) else Color.White.copy(alpha = 0.45f),
                    modifier = Modifier.padding(end = 8.dp).size(20.dp),
                )
                SyncStatusDot(status)
                // Verrouiller l'écran (action, à l'extrémité du bandeau).
                IconButton(onClick = { locked = true }) {
                    Icon(
                        imageVector = Icons.Rounded.Lock,
                        contentDescription = stringResource(R.string.cd_lock),
                        tint = Color.White,
                    )
                }
            }
        }

        // Notice transitoire de présence : le partenaire vient de se dé/reconnecter. Visible même
        // sans toucher l'écran (pas conditionnée aux contrôles) → on sait s'il est là ou pas.
        partnerNotice?.let { back ->
            val name = settings.partnerName
            val text = when {
                back && name.isNotBlank() -> stringResource(R.string.player_partner_back_named, name)
                back -> stringResource(R.string.player_partner_back)
                name.isNotBlank() -> stringResource(R.string.player_partner_gone_named, name)
                else -> stringResource(R.string.player_partner_gone)
            }
            Surface(
                color = if (back) MaterialTheme.colorScheme.primaryContainer else Color.Black.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 110.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = if (back) Icons.Rounded.Person else Icons.Rounded.PersonOff,
                        contentDescription = null,
                        tint = if (back) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                    )
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (back) MaterialTheme.colorScheme.onPrimaryContainer else Color.White,
                    )
                }
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
                    val partner = settings.partnerName
                    val hintText = if (partner.isNotBlank()) {
                        stringResource(
                            if (hint.isPlaying) R.string.player_remote_play_named else R.string.player_remote_pause_named,
                            partner,
                        )
                    } else {
                        stringResource(
                            if (hint.isPlaying) R.string.player_remote_play else R.string.player_remote_pause,
                        )
                    }
                    Text(
                        text = hintText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                }
            }
        }

        // Diagnostic : dérive de synchro estimée (mode debug, activable dans les réglages).
        if (settings.showDebug && !showLobby) {
            Surface(
                color = Color.Black.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 8.dp, top = 56.dp),
            ) {
                Text(
                    text = stringResource(R.string.player_drift, drift),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }

        // Connexion perdue : bannière de reconnexion (le WebSocket retente automatiquement).
        if (status == SyncStatus.Offline && !showLobby && !locked) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 64.dp),
            ) {
                Text(
                    text = stringResource(R.string.player_reconnecting),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        // Salon d'attente : voile bloquant tant que l'autre n'est pas connecté.
        if (showLobby) {
            LobbyOverlay(
                code = session.code,
                movieTitle = session.movieTitle,
                peers = peers,
                partnerName = settings.partnerName,
                isLandscape = isLandscape,
                onCopy = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Miray", session.code))
                },
                onShare = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, session.code) // juste le code
                    }
                    context.startActivity(Intent.createChooser(send, null))
                },
                onEnterAlone = { lobbyBypassed = true },
            )
        }

        // Écran verrouillé : capte tous les taps (anti-touche) + un bouton pour déverrouiller.
        if (locked) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {},
            )
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.45f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp),
            ) {
                IconButton(onClick = { locked = false }) {
                    Icon(
                        imageVector = Icons.Rounded.LockOpen,
                        contentDescription = stringResource(R.string.cd_unlock),
                        tint = Color.White,
                    )
                }
            }
        }

        // Confirmation avant de quitter la session partagée.
        if (showQuitConfirm) {
            AlertDialog(
                onDismissRequest = { showQuitConfirm = false },
                title = { Text(stringResource(R.string.player_quit_title)) },
                text = { Text(stringResource(R.string.player_quit_message)) },
                confirmButton = {
                    TextButton(onClick = { showQuitConfirm = false; onBack() }) {
                        Text(stringResource(R.string.player_quit_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showQuitConfirm = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                },
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

/** Indicateur d'attente ludique : trois points qui rebondissent en cascade (plus fun qu'un spinner). */
@Composable
private fun WaitingDots(color: Color) {
    val transition = rememberInfiniteTransition(label = "waiting-dots")
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val y by transition.animateFloat(
                initialValue = 0f,
                targetValue = -10f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 420),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(i * 140),
                ),
                label = "dot-$i",
            )
            Box(
                modifier = Modifier
                    .offset(y = y.dp)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/**
 * Voile du salon d'attente. **Portrait** : une colonne centrée. **Paysage** : deux colonnes (infos |
 * code + actions) pour tenir en hauteur sans déborder (le voile capte les taps → un scroll y serait
 * bloqué, d'où le choix 2 colonnes).
 */
@Composable
private fun BoxScope.LobbyOverlay(
    code: String,
    movieTitle: String,
    peers: Int,
    partnerName: String,
    isLandscape: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onEnterAlone: () -> Unit,
) {
    // Voile OPAQUE + capture des taps (sans ripple) : rien ne transparaît et les contrôles du lecteur
    // dessous ne sont pas atteignables tant que le partenaire n'est pas là.
    val scrim = Modifier
        .matchParentSize()
        .background(Color.Black)
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) {}
        .statusBarsPadding()
        .navigationBarsPadding()

    if (isLandscape) {
        Row(
            modifier = scrim.padding(horizontal = 40.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                LobbyInfo(movieTitle, partnerName)
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                LobbyActions(code, peers, partnerName, onCopy, onShare, onEnterAlone)
            }
        }
    } else {
        Column(
            modifier = scrim.padding(horizontal = 32.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            LobbyInfo(movieTitle, partnerName)
            Spacer(Modifier.height(28.dp))
            LobbyActions(code, peers, partnerName, onCopy, onShare, onEnterAlone)
        }
    }
}

/** Bloc « infos » du salon : pastille film + titre + nom du film + invitation. */
@Composable
private fun LobbyInfo(movieTitle: String, partnerName: String) {
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
        text = if (partnerName.isNotBlank()) {
            stringResource(R.string.lobby_hint_named, partnerName)
        } else {
            stringResource(R.string.lobby_hint)
        },
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
    )
}

/** Bloc « actions » du salon : carte du code + copier/partager + attente + entrer seul·e. */
@Composable
private fun LobbyActions(
    code: String,
    peers: Int,
    partnerName: String,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onEnterAlone: () -> Unit,
) {
    var copied by remember { mutableStateOf(false) }

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
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = { onCopy(); copied = true }) {
            Icon(
                imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(stringResource(if (copied) R.string.pairing_copied else R.string.pairing_copy))
        }
        Spacer(Modifier.size(8.dp))
        // Partager : icône seule (ouvre la feuille de partage Android).
        FilledTonalIconButton(onClick = onShare) {
            Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.cd_share))
        }
    }

    Spacer(Modifier.height(28.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        WaitingDots(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(12.dp))
        Text(
            text = if (partnerName.isNotBlank()) {
                stringResource(R.string.lobby_waiting_named, partnerName, peers)
            } else {
                stringResource(R.string.lobby_waiting, peers)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
        )
    }
    Spacer(Modifier.height(12.dp))
    TextButton(onClick = onEnterAlone) {
        Text(
            text = stringResource(R.string.lobby_enter_alone),
            color = Color.White.copy(alpha = 0.7f),
        )
    }
}

/** Barre d'emojis de réaction (diffusés à tous les spectateurs). */
@Composable
private fun ReactionBar(
    emojis: List<String>,
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
            emojis.forEach { emoji ->
                ReactionEmoji(emoji = emoji, onReact = onReact)
            }
        }
    }
}

/** Un emoji de réaction : pas de fond/ripple, juste un petit agrandissement à l'appui. */
@Composable
private fun ReactionEmoji(emoji: String, onReact: (String) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (pressed) 1.4f else 1f, label = "reaction-scale")
    Text(
        text = emoji,
        fontSize = 26.sp,
        modifier = Modifier
            .clickable(
                interactionSource = interaction,
                indication = null, // pas de fond gris : seul l'emoji s'agrandit
                onClickLabel = stringResource(R.string.cd_react, emoji),
                onClick = { onReact(emoji) },
            )
            .scale(scale)
            .padding(8.dp),
    )
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
    bottomPadding: Dp,
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
                .padding(end = 24.dp, bottom = bottomPadding)
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
