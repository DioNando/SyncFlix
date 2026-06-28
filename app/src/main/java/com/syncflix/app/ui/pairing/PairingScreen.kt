package com.syncflix.app.ui.pairing

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.syncflix.app.R
import com.syncflix.app.data.model.SessionState
import com.syncflix.app.data.remote.SessionApi
import com.syncflix.app.data.remote.SessionException
import com.syncflix.app.data.settings.SettingsStore
import com.syncflix.app.ui.components.ActionCard
import com.syncflix.app.ui.components.CodeField
import com.syncflix.app.ui.components.SyncFlixFieldShape
import com.syncflix.app.ui.components.syncflixFieldColors
import kotlinx.coroutines.launch

/**
 * Écran d'appairage : adresse du serveur + code de session.
 *
 * « Créer une session » appelle `POST /api/sessions` puis affiche le **panneau du code** (à partager
 * avant d'entrer dans le lecteur) ; « Rejoindre » appelle `POST /api/sessions/{code}/join` et entre
 * directement. Conforme à la charte : style tonal, en-tête de marque, volet scrollable + `imePadding`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingScreen(
    onConnect: (SessionState) -> Unit,
    onPickMovie: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSearch: (String) -> Unit,
) {
    val api = remember { SessionApi() }
    val scope = rememberCoroutineScope()

    // Pré-rempli avec l'adresse serveur par défaut des réglages (modifiable ici).
    var server by remember { mutableStateOf(SettingsStore.settings.defaultServer) }
    var code by remember { mutableStateOf("") }
    var serverError by remember { mutableStateOf(false) }
    var codeError by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }

    /** Lance un appel réseau (join) avec gestion du chargement et des erreurs. */
    fun run(onSuccess: (SessionState) -> Unit, call: suspend () -> SessionState) {
        errorRes = null
        loading = true
        scope.launch {
            try {
                onSuccess(call())
            } catch (e: SessionException) {
                errorRes = if (e.statusCode == 404) {
                    R.string.pairing_error_not_found
                } else {
                    R.string.pairing_error_network
                }
            } catch (e: Exception) {
                errorRes = R.string.pairing_error_network
            } finally {
                loading = false
            }
        }
    }

    // « Créer » = choisir d'abord le film (l'adresse serveur doit être renseignée) ; la session est
    // créée à la sélection du film. Le code à partager s'affiche ensuite dans le salon d'attente.
    fun create() {
        serverError = server.isBlank()
        if (!serverError) onPickMovie(server)
    }

    fun join() {
        serverError = server.isBlank()
        codeError = code.isBlank()
        if (!serverError && !codeError) run(onSuccess = onConnect) { api.join(server, code) }
    }

    // Catalogue/watchlist : nécessite l'adresse serveur (appels au proxy TMDB).
    fun openSearch() {
        serverError = server.isBlank()
        if (!serverError) onOpenSearch(server)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                actions = {
                    IconButton(onClick = ::openSearch) {
                        Icon(
                            imageVector = Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.search_title),
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.cd_settings),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // En-tête de marque : logo AU-DESSUS du nom (empilé, centré).
            // Logo bicolore (bleu + or) → pas de teinte, on garde ses couleurs d'origine.
            Image(
                painter = painterResource(R.drawable.logo),
                contentDescription = stringResource(R.string.cd_logo),
                modifier = Modifier.size(80.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(40.dp))

            PairingForm(
                server = server,
                code = code,
                serverError = serverError,
                codeError = codeError,
                loading = loading,
                errorRes = errorRes,
                onServerChange = { server = it; serverError = false },
                onCodeChange = { code = it; codeError = false },
                onJoin = ::join,
                onCreate = ::create,
            )
        }
    }
}

@Composable
private fun PairingForm(
    server: String,
    code: String,
    serverError: Boolean,
    codeError: Boolean,
    loading: Boolean,
    errorRes: Int?,
    onServerChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onJoin: () -> Unit,
    onCreate: () -> Unit,
) {
    TextField(
        value = server,
        onValueChange = onServerChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !loading,
        label = { Text(stringResource(R.string.pairing_server_label)) },
        placeholder = { Text(stringResource(R.string.pairing_server_hint)) },
        singleLine = true,
        isError = serverError,
        supportingText = if (serverError) {
            { Text(stringResource(R.string.pairing_error_server)) }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        shape = SyncFlixFieldShape,
        colors = syncflixFieldColors(),
    )

    Spacer(Modifier.height(20.dp))

    // Code d'appairage : 6 cases (le code fait toujours 6 caractères).
    Text(
        text = stringResource(R.string.pairing_code_label),
        style = MaterialTheme.typography.labelLarge,
        color = if (codeError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, bottom = 8.dp),
    )
    CodeField(
        value = code,
        onValueChange = onCodeChange,
        enabled = !loading,
        isError = codeError,
        contentDescription = stringResource(R.string.pairing_code_label),
    )
    if (codeError) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.pairing_error_code),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
        )
    }

    errorRes?.let { res ->
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(res),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(Modifier.height(32.dp))

    if (loading) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    } else {
        ActionCard(
            icon = Icons.Rounded.PlayArrow,
            label = stringResource(R.string.pairing_join),
            accent = MaterialTheme.colorScheme.primary,
            onClick = onJoin,
            filled = true,
        )

        Spacer(Modifier.height(12.dp))

        ActionCard(
            icon = Icons.Rounded.Add,
            label = stringResource(R.string.pairing_create),
            // Conteneur secondaire = or pâle #FFF2A1 (texte foncé), même teinte dans les deux thèmes.
            accent = MaterialTheme.colorScheme.secondaryContainer,
            onClick = onCreate,
            filled = true,
        )
    }
}
