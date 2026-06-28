<?php

use App\Http\Controllers\CatalogController;
use App\Http\Controllers\MovieController;
use App\Http\Controllers\SessionController;
use App\Http\Controllers\StreamController;
use Illuminate\Support\Facades\Route;

/*
|--------------------------------------------------------------------------
| Routes API (préfixe /api)
|--------------------------------------------------------------------------
| Streaming vidéo (étape 1) + sessions de visionnage (étape 3, hors broadcast).
| Le canal privé Reverb branché sur la session arrive avec le temps réel.
*/

// Horloge serveur (ClockSync HTTP : offset d'horloge mobile↔serveur).
Route::get('/time', [SessionController::class, 'time']);

// Auth d'abonnement au canal de présence Reverb (signe si le code de session existe).
Route::post('/broadcasting/auth', [SessionController::class, 'authChannel']);

// Bibliothèque de films (choix dans l'app).
Route::get('/movies', [MovieController::class, 'index']);

// Streaming : diffuse le fichier vidéo (Range/206 géré par BinaryFileResponse).
Route::get('/movies/{movie}/stream', [StreamController::class, 'stream']);
// Piste de sous-titres sidecar (index = position dans la liste du film).
Route::get('/movies/{movie}/subtitles/{index}', [StreamController::class, 'subtitle'])
    ->whereNumber('index');

// Sessions de visionnage (état partagé autoritatif).
Route::post('/sessions', [SessionController::class, 'create']);              // crée + renvoie un code
Route::post('/sessions/{code}/join', [SessionController::class, 'join']);    // rejoint via le code
Route::get('/sessions/{code}/state', [SessionController::class, 'show']);    // état courant (resync)
Route::post('/sessions/{code}/state', [SessionController::class, 'update']); // maj Play/Pause/Seek
Route::post('/sessions/{code}/reaction', [SessionController::class, 'react']); // réaction emoji

// Catalogue TMDB (recherche) + watchlist « à regarder plus tard ».
Route::get('/search', [CatalogController::class, 'search']);
Route::get('/movies-meta/{tmdbId}', [CatalogController::class, 'detail'])->whereNumber('tmdbId');
Route::get('/wishlist', [CatalogController::class, 'wishlist']);
Route::post('/wishlist', [CatalogController::class, 'store']);
Route::patch('/wishlist/{wishlistItem}', [CatalogController::class, 'update'])->whereNumber('wishlistItem');
Route::delete('/wishlist/{wishlistItem}', [CatalogController::class, 'destroy'])->whereNumber('wishlistItem');
