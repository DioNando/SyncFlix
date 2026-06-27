package com.syncflix.app.data.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Réglages persistés de Miray (cf. SettingsScreen). */
data class MiraySettings(
    /** Adresse serveur pré-remplie sur l'écran d'appairage. */
    val defaultServer: String = "",
    /** Nom affiché à la place de « ton/ta partenaire » (vide = libellé générique). */
    val partnerName: String = "",
    /** Affiche la barre + les réactions emoji dans le lecteur. */
    val showReactions: Boolean = true,
    /** Affiche le titre du film dans le bandeau du lecteur. */
    val showMovieTitle: Boolean = true,
    /** Jeu d'emojis proposés dans la barre de réactions. */
    val reactions: List<String> = SettingsStore.DEFAULT_REACTIONS,
)

/**
 * Préférences utilisateur, façon WorkSync mais sans dépendance : [SharedPreferences] exposées en
 * **état Compose** global (lecture réactive dans n'importe quel écran), persistées à chaque écriture.
 *
 * Initialisé une fois au démarrage (`SyncFlixApp.onCreate`).
 */
object SettingsStore {

    val DEFAULT_REACTIONS = listOf("❤️", "😂", "😮", "👍", "🔥")

    private const val PREFS = "miray_settings"
    private const val KEY_SERVER = "default_server"
    private const val KEY_PARTNER = "partner_name"
    private const val KEY_SHOW_REACTIONS = "show_reactions"
    private const val KEY_SHOW_TITLE = "show_movie_title"
    private const val KEY_REACTIONS = "reactions"

    private lateinit var prefs: SharedPreferences

    /** État lu par l'UI ; recomposition automatique à chaque mise à jour. */
    var settings by mutableStateOf(MiraySettings())
        private set

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        settings = MiraySettings(
            defaultServer = prefs.getString(KEY_SERVER, "").orEmpty(),
            partnerName = prefs.getString(KEY_PARTNER, "").orEmpty(),
            showReactions = prefs.getBoolean(KEY_SHOW_REACTIONS, true),
            showMovieTitle = prefs.getBoolean(KEY_SHOW_TITLE, true),
            reactions = prefs.getString(KEY_REACTIONS, null)
                ?.split(" ")?.filter { it.isNotBlank() }
                ?.ifEmpty { DEFAULT_REACTIONS } ?: DEFAULT_REACTIONS,
        )
    }

    fun setDefaultServer(value: String) = update { it.copy(defaultServer = value.trim()) }
    fun setPartnerName(value: String) = update { it.copy(partnerName = value.trim()) }
    fun setShowReactions(value: Boolean) = update { it.copy(showReactions = value) }
    fun setShowMovieTitle(value: Boolean) = update { it.copy(showMovieTitle = value) }

    /** Nombre maximum d'emojis dans la barre de réactions. */
    const val MAX_REACTIONS = 6

    /** Définit le jeu d'emojis (cases vides ignorées, borné à [MAX_REACTIONS]) ; aucun = défaut. */
    fun setReactions(list: List<String>) {
        val cleaned = list.map { it.trim() }.filter { it.isNotBlank() }.take(MAX_REACTIONS)
        update { it.copy(reactions = cleaned.ifEmpty { DEFAULT_REACTIONS }) }
    }

    private inline fun update(transform: (MiraySettings) -> MiraySettings) {
        val next = transform(settings)
        settings = next
        prefs.edit()
            .putString(KEY_SERVER, next.defaultServer)
            .putString(KEY_PARTNER, next.partnerName)
            .putBoolean(KEY_SHOW_REACTIONS, next.showReactions)
            .putBoolean(KEY_SHOW_TITLE, next.showMovieTitle)
            .putString(KEY_REACTIONS, next.reactions.joinToString(" "))
            .apply()
    }
}
