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
}
