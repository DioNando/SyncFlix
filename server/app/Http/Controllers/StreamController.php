<?php

namespace App\Http\Controllers;

use App\Models\Movie;
use Illuminate\Support\Facades\Storage;
use Symfony\Component\HttpFoundation\BinaryFileResponse;

class StreamController extends Controller
{
    /**
     * Diffuse le fichier vidéo d'un film.
     *
     * Renvoie une `BinaryFileResponse` : Symfony y gère **nativement** le header `Range`
     * (réponse `206 Partial Content` + `Accept-Ranges: bytes`), ce qui permet à ExoPlayer
     * de sauter dans la timeline sans télécharger tout le fichier (cf. ARCHITECTURE.md).
     */
    public function stream(Movie $movie): BinaryFileResponse
    {
        $disk = Storage::disk('local');

        abort_unless($disk->exists($movie->path), 404, 'Fichier vidéo introuvable.');

        return response()->file($disk->path($movie->path), [
            'Content-Type' => 'video/mp4',
        ]);
    }

    /**
     * Diffuse une piste de sous-titres « sidecar » du film ([index] = position dans `subtitles`).
     *
     * Le type MIME (`text/vtt` / `application/x-subrip`) est celui détecté au scan, pour qu'ExoPlayer
     * sache parser la piste. Sélection 100 % locale côté app (aucune synchro entre spectateurs).
     */
    public function subtitle(Movie $movie, int $index): BinaryFileResponse
    {
        $subtitles = $movie->subtitles ?? [];
        abort_unless(isset($subtitles[$index]), 404, 'Sous-titre introuvable.');

        $sub = $subtitles[$index];
        $disk = Storage::disk('local');
        abort_unless($disk->exists($sub['path']), 404, 'Fichier de sous-titres introuvable.');

        return response()->file($disk->path($sub['path']), [
            'Content-Type' => $sub['mime'],
        ]);
    }
}
