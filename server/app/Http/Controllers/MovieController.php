<?php

namespace App\Http\Controllers;

use App\Models\Movie;

/**
 * Bibliothèque de films : liste à choisir dans l'app (le streaming reste sur StreamController).
 */
class MovieController extends Controller
{
    /** Liste des films disponibles + leurs pistes de sous-titres (cf. movies:scan). */
    public function index()
    {
        return Movie::query()
            ->orderBy('title')
            ->get()
            ->map(fn (Movie $movie) => self::serialize($movie))
            ->all();
    }

    /** DTO d'un film exposé à l'app (réutilisé par SessionController pour l'état de session). */
    public static function serialize(Movie $movie): array
    {
        $subtitles = collect($movie->subtitles ?? [])
            ->values()
            ->map(fn (array $sub, int $i) => [
                'index' => $i,
                'lang' => $sub['lang'],
                'label' => $sub['label'],
                'mime' => $sub['mime'],
            ])
            ->all();

        return [
            'id' => $movie->id,
            'title' => $movie->title,
            // Segment servi par Caddy en file_server (/media/{stream_path}). Encodé pour gérer
            // les espaces/caractères spéciaux des noms de fichiers. Fallback : /api/movies/{id}/stream.
            'stream_path' => rawurlencode(basename($movie->path)),
            'subtitles' => $subtitles,
        ];
    }
}
