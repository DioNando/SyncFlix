<?php

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

// Streaming : diffuse le fichier vidéo (Range/206 géré par BinaryFileResponse).
Route::get('/movies/{movie}/stream', [StreamController::class, 'stream']);

// Sessions de visionnage (état partagé autoritatif).
Route::post('/sessions', [SessionController::class, 'create']);              // crée + renvoie un code
Route::post('/sessions/{code}/join', [SessionController::class, 'join']);    // rejoint via le code
Route::get('/sessions/{code}/state', [SessionController::class, 'show']);    // état courant (resync)
Route::post('/sessions/{code}/state', [SessionController::class, 'update']); // maj Play/Pause/Seek
