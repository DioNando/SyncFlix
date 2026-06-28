package com.syncflix.app.ui.catalog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syncflix.app.R
import com.syncflix.app.data.model.TmdbMovie
import com.syncflix.app.data.remote.CatalogApi
import com.syncflix.app.ui.components.SyncFlixFieldShape
import com.syncflix.app.ui.components.syncflixFieldColors
import kotlinx.coroutines.launch

/**
 * Recherche de films (proxy TMDB). Champ avec **debounce** → grille d'affiches ; un tap ouvre une
 * fiche (bottom sheet) avec synopsis + bouton « Ajouter à ma liste ». Accès à la watchlist en barre.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    server: String,
    onOpenWatchlist: () -> Unit,
    onBack: () -> Unit,
) {
    val api = remember { CatalogApi() }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<TmdbMovie>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<TmdbMovie?>(null) }

    // Recherche déclenchée à la frappe, avec un délai pour ne pas spammer le serveur/TMDB.
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList(); loading = false; error = false
            return@LaunchedEffect
        }
        loading = true; error = false
        kotlinx.coroutines.delay(400)
        runCatching { api.search(server, query) }
            .onSuccess { results = it }
            .onFailure { error = true }
        loading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.search_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    IconButton(onClick = onOpenWatchlist) {
                        Icon(Icons.Rounded.Bookmarks, stringResource(R.string.search_open_watchlist))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                label = { Text(stringResource(R.string.search_field_label)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                shape = SyncFlixFieldShape,
                colors = syncflixFieldColors(),
            )

            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when {
                    loading -> CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    error -> StatusText(stringResource(R.string.search_error))
                    query.isBlank() -> StatusText(stringResource(R.string.search_hint))
                    results.isEmpty() -> StatusText(stringResource(R.string.search_empty))
                    else -> LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(results, key = { it.tmdbId }) { movie ->
                            Column(modifier = Modifier.clickable { selected = movie }) {
                                MoviePoster(
                                    posterUrl = movie.posterUrl,
                                    contentDescription = movie.title,
                                    modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
                                )
                                Text(
                                    text = movie.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selected?.let { movie ->
        DetailSheet(server = server, movie = movie, api = api, onDismiss = { selected = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailSheet(
    server: String,
    movie: TmdbMovie,
    api: CatalogApi,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var added by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Row {
                MoviePoster(
                    posterUrl = movie.posterUrl,
                    contentDescription = movie.title,
                    modifier = Modifier.width(110.dp).aspectRatio(2f / 3f),
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = movie.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    movie.year?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            movie.overview?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(
                enabled = !adding && !added,
                onClick = {
                    adding = true
                    scope.launch {
                        runCatching { api.add(server, movie) }.onSuccess { added = true }
                        adding = false
                    }
                },
            ) {
                Icon(
                    imageVector = if (added) Icons.Rounded.Check else Icons.Rounded.Bookmarks,
                    contentDescription = null,
                    modifier = Modifier.height(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (added) R.string.search_added else R.string.search_add))
            }
            Spacer(Modifier.height(8.dp))
        }
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
