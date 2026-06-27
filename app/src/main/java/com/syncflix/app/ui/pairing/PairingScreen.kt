package com.syncflix.app.ui.pairing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syncflix.app.R
import com.syncflix.app.data.model.SessionState
import com.syncflix.app.data.remote.SessionApi
import com.syncflix.app.data.remote.SessionException
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
@Composable
fun PairingScreen(
    onConnect: (SessionState) -> Unit,
) {
    val api = remember { SessionApi() }
    val scope = rememberCoroutineScope()

    var server by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var serverError by remember { mutableStateOf(false) }
    var codeError by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var errorRes by remember { mutableStateOf<Int?>(null) }
    // Session créée en attente de partage : tant qu'elle est non nulle, on affiche le panneau du code.
    var created by remember { mutableStateOf<SessionState?>(null) }

    /** Lance un appel réseau (create/join) avec gestion du chargement et des erreurs. */
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

    fun create() {
        serverError = server.isBlank()
        if (!serverError) run(onSuccess = { created = it }) { api.create(server) }
    }

    fun join() {
        serverError = server.isBlank()
        codeError = code.isBlank()
        if (!serverError && !codeError) run(onSuccess = onConnect) { api.join(server, code) }
    }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // En-tête de marque (logo + nom) — pattern commun à toutes les apps.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.logo),
                    contentDescription = stringResource(R.string.cd_logo),
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.app_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(40.dp))

            val pending = created
            if (pending != null) {
                CodeReadyPanel(code = pending.code, onStart = { onConnect(pending) })
            } else {
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

    Spacer(Modifier.height(16.dp))

    TextField(
        value = code,
        onValueChange = onCodeChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !loading,
        label = { Text(stringResource(R.string.pairing_code_label)) },
        placeholder = { Text(stringResource(R.string.pairing_code_hint)) },
        singleLine = true,
        isError = codeError,
        supportingText = if (codeError) {
            { Text(stringResource(R.string.pairing_error_code)) }
        } else null,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Text,
        ),
        shape = SyncFlixFieldShape,
        colors = syncflixFieldColors(),
    )

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
        Button(
            onClick = onJoin,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(stringResource(R.string.pairing_join))
        }

        Spacer(Modifier.height(12.dp))

        FilledTonalButton(
            onClick = onCreate,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(stringResource(R.string.pairing_create))
        }
    }
}

/** Panneau affiché après création : le code à partager + copie + bouton pour entrer dans le lecteur. */
@Composable
private fun CodeReadyPanel(
    code: String,
    onStart: () -> Unit,
) {
    val context = LocalContext.current
    var copied by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.pairing_code_ready_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = code,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 8.sp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.pairing_code_ready_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    FilledTonalButton(
        onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("SyncFlix", code))
            copied = true
        },
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Text(stringResource(if (copied) R.string.pairing_copied else R.string.pairing_copy))
    }

    Spacer(Modifier.height(12.dp))

    Button(
        onClick = onStart,
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Text(stringResource(R.string.pairing_start))
    }
}
