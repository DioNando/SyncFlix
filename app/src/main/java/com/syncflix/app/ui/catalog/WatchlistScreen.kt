package com.syncflix.app.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.syncflix.app.R
import com.syncflix.app.data.model.WishlistItem
import com.syncflix.app.data.remote.CatalogApi
import com.syncflix.app.ui.components.SyncFlixFieldShape
import kotlinx.coroutines.launch

/** Watchlist « à regarder plus tard » : affiche + badge disponibilité + « vu » + retrait. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchlistScreen(
    server: String,
    onBack: () -> Unit,
) {
    val api = remember { CatalogApi() }
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<WishlistItem>?>(null) }
    var error by remember { mutableStateOf(false) }

    LaunchedEffect(server) {
        runCatching { api.wishlist(server) }
            .onSuccess { items = it }
            .onFailure { error = true }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.watchlist_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
            val current = items
            when {
                error -> StatusText(stringResource(R.string.watchlist_error))
                current == null -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                current.isEmpty() -> StatusText(stringResource(R.string.watchlist_empty))
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(current, key = { it.id }) { item ->
                        WishRow(
                            item = item,
                            onWatchedChange = { watched ->
                                scope.launch {
                                    runCatching { api.setWatched(server, item.id, watched) }
                                        .onSuccess { updated -> items = items?.map { if (it.id == updated.id) updated else it } }
                                }
                            },
                            onRemove = {
                                scope.launch {
                                    runCatching { api.remove(server, item.id) }
                                        .onSuccess { items = items?.filterNot { it.id == item.id } }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WishRow(
    item: WishlistItem,
    onWatchedChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Surface(
        shape = SyncFlixFieldShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            MoviePoster(
                posterUrl = item.posterUrl,
                contentDescription = item.title,
                modifier = Modifier.width(54.dp).aspectRatio(2f / 3f),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                item.year?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                AvailabilityBadge(available = item.available)
            }
            Checkbox(checked = item.watched, onCheckedChange = onWatchedChange)
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.cd_remove))
            }
        }
    }
}

@Composable
private fun AvailabilityBadge(available: Boolean) {
    val (label, container, content) = if (available) {
        Triple(R.string.watchlist_available, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
    } else {
        Triple(R.string.watchlist_unavailable, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(shape = MaterialTheme.shapes.small, color = container) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = content,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(32.dp),
    )
}
