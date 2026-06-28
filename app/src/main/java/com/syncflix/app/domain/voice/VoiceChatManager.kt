package com.syncflix.app.domain.voice

import android.content.Context
import android.media.AudioManager
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule

/**
 * Voice chat **push-to-talk** entre les deux spectateurs, en **WebRTC P2P**.
 *
 * - **Connectivité** : aucun serveur ICE (`iceServers` vide). Sur le tailnet (Tailscale), les deux
 *   appareils se voient en *host candidates* (IP `100.x`) → connexion directe, sans STUN/TURN.
 * - **Signalisation** : offer / answer / ICE échangés via des **client events** du canal de présence
 *   Reverb ([sendSignal]) — pas de code serveur. La collision d'offres est évitée par une règle
 *   déterministe : **le clientId le plus grand initie l'offre**.
 * - **Push-to-talk** : la piste micro est **désactivée par défaut** ; [setTalking] l'active tant que
 *   le bouton est maintenu. La connexion reste établie ; on ne fait qu'activer/couper la piste.
 *
 * Les callbacks WebRTC arrivent sur des threads internes : l'appelant ([onRemoteTalking],
 * [onConnectedChange]) doit reposter sur le thread UI s'il met à jour de l'état Compose.
 */
class VoiceChatManager(
    context: Context,
    private val myId: String,
    /**
     * Hôte du serveur TURN/STUN (= l'hôte du serveur Miray, joignable via Tailscale par les deux
     * téléphones). **Indispensable dès que les pairs sont sur des réseaux différents** (ex. WiFi vs
     * données mobiles) : WebRTC sur Android ne récupère pas l'IP `100.x` du tun Tailscale, donc sans
     * relais TURN aucune paire de candidats ne fonctionne et ICE échoue. `null`/vide = P2P direct seul.
     */
    private val turnHost: String?,
    /** Émet un client event `client-voice-<type>` (relayé tel quel au pair). */
    private val sendSignal: (type: String, payload: JSONObject) -> Unit,
    /** Le pair vient de commencer/arrêter de parler (pour le ducking + l'indicateur). */
    private val onRemoteTalking: (Boolean) -> Unit,
    /** État du lien P2P (true = audio établi). */
    private val onConnectedChange: (Boolean) -> Unit = {},
) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // État audio à restaurer en fin de session voix (on bascule le téléphone en mode communication).
    private var savedAudioMode = AudioManager.MODE_NORMAL
    private var savedSpeakerphoneOn = false

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var peerId: String? = null
    private var started = false

    // ICE reçue avant que la description distante ne soit posée : WebRTC la rejetterait → on la met
    // en attente et on la rejoue une fois `remoteDescription` appliquée.
    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    /** Initialise la fabrique WebRTC + la piste micro locale (muette). Idempotent. */
    fun start() {
        if (started) return
        started = true

        // **Routage audio indispensable** : sans ça, la voix WebRTC reçue est rendue sur l'écouteur
        // (oreille) à très faible volume et est noyée par le son du film sur le haut-parleur — c'est
        // la raison classique pour laquelle « on voit l'autre parler mais on ne l'entend pas ».
        // On bascule le téléphone en mode communication + haut-parleur, restauré dans stop().
        savedAudioMode = audioManager.mode
        @Suppress("DEPRECATION")
        savedSpeakerphoneOn = audioManager.isSpeakerphoneOn
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        audioManager.isSpeakerphoneOn = true

        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions(),
        )
        // AEC/NS matériels quand disponibles : limite l'écho (utile même en PTT half-duplex).
        val audioDeviceModule = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        val factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDeviceModule)
            .createPeerConnectionFactory()
        this.factory = factory

        audioSource = factory.createAudioSource(MediaConstraints())
        localAudioTrack = factory.createAudioTrack(AUDIO_TRACK_ID, audioSource).apply {
            setEnabled(false) // push-to-talk : muet tant que le bouton n'est pas maintenu
        }
    }

    /** Le pair est présent (id = son clientId) : prépare la connexion et émet l'offre si on est l'initiateur. */
    fun onPeerPresent(remoteId: String) {
        if (!started || remoteId == myId) return
        // Déjà en cours de négociation/connecté avec ce pair : ne pas ré-émettre d'offre.
        if (peerId == remoteId && peerConnection != null) return
        peerId = remoteId
        if (peerConnection == null) peerConnection = createPeerConnection()
        // Un seul côté émet l'offre (anti-collision) : le clientId le plus grand.
        if (myId > remoteId) createOffer()
    }

    /** Le pair est parti : on ferme la connexion (repart proprement à son retour). */
    fun onPeerLeft() {
        peerId = null
        closePeerConnection()
        onConnectedChange(false)
    }

    /** Reçoit une trame de signalisation (`offer` / `answer` / `ice` / `talk`). */
    fun onSignal(type: String, payload: JSONObject) {
        if (!started) return
        when (type) {
            "offer" -> handleOffer(payload)
            "answer" -> handleAnswer(payload)
            "ice" -> handleIce(payload)
            "talk" -> onRemoteTalking(payload.optBoolean("talking", false))
        }
    }

    /** Active/coupe la piste micro (push-to-talk) et prévient le pair (ducking + indicateur). */
    fun setTalking(talking: Boolean) {
        localAudioTrack?.setEnabled(talking)
        sendSignal("talk", JSONObject().put("talking", talking))
    }

    /** Libère toutes les ressources WebRTC. */
    fun stop() {
        closePeerConnection()
        localAudioTrack = null
        audioSource?.dispose(); audioSource = null
        factory?.dispose(); factory = null
        if (started) {
            // Restaure le routage audio d'origine (sinon le téléphone reste en mode communication).
            audioManager.mode = savedAudioMode
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = savedSpeakerphoneOn
        }
        started = false
    }

    // --- Interne ----------------------------------------------------------------------------------

    private fun createPeerConnection(): PeerConnection? {
        val factory = factory ?: return null
        // STUN + TURN sur le serveur Miray (joignable via Tailscale). Le TURN relaie l'audio quand
        // le P2P direct est impossible (réseaux différents : WiFi ↔ données mobiles). Sans lui, ICE
        // échoue dans ce cas (cf. doc de [turnHost]).
        val iceServers = buildList {
            val host = turnHost
            if (!host.isNullOrBlank()) {
                add(PeerConnection.IceServer.builder("stun:$host:$TURN_PORT").createIceServer())
                add(
                    PeerConnection.IceServer.builder("turn:$host:$TURN_PORT?transport=udp")
                        .setUsername(TURN_USER).setPassword(TURN_PASSWORD).createIceServer(),
                )
                add(
                    PeerConnection.IceServer.builder("turn:$host:$TURN_PORT?transport=tcp")
                        .setUsername(TURN_USER).setPassword(TURN_PASSWORD).createIceServer(),
                )
            }
        }
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                sendSignal(
                    "ice",
                    JSONObject()
                        .put("sdpMid", candidate.sdpMid)
                        .put("sdpMLineIndex", candidate.sdpMLineIndex)
                        .put("candidate", candidate.sdp),
                )
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                onConnectedChange(
                    state == PeerConnection.IceConnectionState.CONNECTED ||
                        state == PeerConnection.IceConnectionState.COMPLETED,
                )
            }

            // En Unified Plan, l'audio distant est joué automatiquement par l'AudioDeviceModule
            // (onTrack/onRemoveTrack sont des méthodes default : pas besoin de les surcharger).
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        }) ?: return null

        localAudioTrack?.let { pc.addTrack(it, listOf(STREAM_ID)) }
        return pc
    }

    private fun createOffer() {
        val pc = peerConnection ?: return
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(SimpleSdpObserver(), sdp)
                sendSignal("offer", sdp.toJson())
            }
        }, audioConstraints())
    }

    private fun handleOffer(payload: JSONObject) {
        if (peerConnection == null) peerConnection = createPeerConnection()
        val pc = peerConnection ?: return
        pc.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() {
                    onRemoteDescriptionSet()
                    pc.createAnswer(object : SimpleSdpObserver() {
                        override fun onCreateSuccess(sdp: SessionDescription) {
                            pc.setLocalDescription(SimpleSdpObserver(), sdp)
                            sendSignal("answer", sdp.toJson())
                        }
                    }, audioConstraints())
                }
            },
            payload.toSessionDescription(),
        )
    }

    private fun handleAnswer(payload: JSONObject) {
        val pc = peerConnection ?: return
        pc.setRemoteDescription(
            object : SimpleSdpObserver() {
                override fun onSetSuccess() = onRemoteDescriptionSet()
            },
            payload.toSessionDescription(),
        )
    }

    private fun handleIce(payload: JSONObject) {
        val candidate = IceCandidate(
            payload.optString("sdpMid"),
            payload.optInt("sdpMLineIndex"),
            payload.optString("candidate"),
        )
        val pc = peerConnection
        if (pc != null && remoteDescriptionSet) pc.addIceCandidate(candidate) else pendingIce.add(candidate)
    }

    private fun onRemoteDescriptionSet() {
        remoteDescriptionSet = true
        val pc = peerConnection ?: return
        pendingIce.forEach { pc.addIceCandidate(it) }
        pendingIce.clear()
    }

    private fun closePeerConnection() {
        peerConnection?.dispose()
        peerConnection = null
        pendingIce.clear()
        remoteDescriptionSet = false
    }

    private fun SessionDescription.toJson(): JSONObject =
        JSONObject().put("type", type.canonicalForm()).put("sdp", description)

    private fun JSONObject.toSessionDescription(): SessionDescription =
        SessionDescription(SessionDescription.Type.fromCanonicalForm(getString("type")), getString("sdp"))

    private fun audioConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
    }

    /** Observateur SDP avec implémentations vides par défaut (on n'override que l'utile). */
    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    private companion object {
        // TURN/STUN : identifiants statiques (comme la clé Reverb), doivent matcher server/turn/turnserver.conf.
        const val TURN_PORT = 3478
        const val TURN_USER = "miray"
        const val TURN_PASSWORD = "miray-turn-2026"
        const val AUDIO_TRACK_ID = "miray-audio"
        const val STREAM_ID = "miray-voice"
    }
}
