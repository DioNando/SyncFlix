package com.syncflix.app.ui.settings

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.syncflix.app.R
import com.syncflix.app.data.settings.SettingsStore
import com.syncflix.app.ui.components.SyncFlixFieldShape
import com.syncflix.app.ui.components.syncflixFieldColors

/**
 * Écran de réglages (inspiré de WorkSync : sections titrées + switches/champs tonals).
 *
 * Lit/écrit directement [SettingsStore] (état Compose global, persisté). Les champs gardent un état
 * local pour une saisie fluide et propagent au store à chaque frappe.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settings = SettingsStore.settings

    var server by remember { mutableStateOf(settings.defaultServer) }
    var partner by remember { mutableStateOf(settings.partnerName) }
    // 6 cases d'emoji (rembourrées à MAX_REACTIONS) — une saisie par case (plus intuitif qu'un espace).
    var slots by remember { mutableStateOf(padReactions(settings.reactions)) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            // --- Serveur ---
            SectionHeader(stringResource(R.string.settings_section_server))
            TextField(
                value = server,
                onValueChange = { server = it; SettingsStore.setDefaultServer(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_default_server_label)) },
                placeholder = { Text(stringResource(R.string.pairing_server_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = SyncFlixFieldShape,
                colors = syncflixFieldColors(),
            )

            // --- Partenaire ---
            Spacer(Modifier.height(20.dp))
            SectionHeader(stringResource(R.string.settings_section_partner))
            TextField(
                value = partner,
                onValueChange = { partner = it; SettingsStore.setPartnerName(it) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.settings_partner_label)) },
                singleLine = true,
                shape = SyncFlixFieldShape,
                colors = syncflixFieldColors(),
            )

            // --- Lecteur ---
            Spacer(Modifier.height(20.dp))
            SectionHeader(stringResource(R.string.settings_section_player))
            SettingSwitch(
                label = stringResource(R.string.settings_show_title),
                checked = settings.showMovieTitle,
                onCheckedChange = SettingsStore::setShowMovieTitle,
            )
            SettingSwitch(
                label = stringResource(R.string.settings_show_reactions),
                checked = settings.showReactions,
                onCheckedChange = SettingsStore::setShowReactions,
            )

            // --- Réactions ---
            Spacer(Modifier.height(20.dp))
            SectionHeader(stringResource(R.string.settings_section_reactions))
            Text(
                text = stringResource(R.string.settings_reactions_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                slots.forEachIndexed { i, value ->
                    EmojiSlot(
                        value = value,
                        onValueChange = { v ->
                            val next = slots.toMutableList().also { it[i] = v }
                            slots = next
                            SettingsStore.setReactions(next)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            TextButton(
                onClick = {
                    SettingsStore.setReactions(SettingsStore.DEFAULT_REACTIONS)
                    slots = padReactions(SettingsStore.DEFAULT_REACTIONS)
                },
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_reactions_reset))
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
    )
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Case d'emoji unique (une réaction par case). On retire les espaces (séparateur de stockage). */
@Composable
private fun EmojiSlot(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    BasicTextField(
        value = value,
        onValueChange = { onValueChange(it.filter { c -> !c.isWhitespace() }.take(12)) },
        singleLine = true,
        textStyle = TextStyle(
            fontSize = 24.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier,
        decorationBox = { inner ->
            Box(
                modifier = Modifier
                    .clip(SyncFlixFieldShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .fillMaxWidth()
                    .height(56.dp),
                contentAlignment = Alignment.Center,
            ) { inner() }
        },
    )
}

/** Rembourre la liste d'emojis à exactement [SettingsStore.MAX_REACTIONS] cases (vides à la fin). */
private fun padReactions(list: List<String>): List<String> =
    (list + List(SettingsStore.MAX_REACTIONS) { "" }).take(SettingsStore.MAX_REACTIONS)
