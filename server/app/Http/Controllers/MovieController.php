<?php

namespace App\Http\Controllers;

use App\Models\Movie;
use Illuminate\Support\Facades\Storage;

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

        // URL Caddy = /media/{id}.{ext} (lien propre créé par movies:scan) → aucun caractère spécial
        // dans l'URL (les crochets/parenthèses des noms de release faisaient échouer Caddy en 404).
        // Si le lien n'a pas pu être créé, on laisse vide → l'app bascule sur /api/movies/{id}/stream.
        $ext = strtolower(pathinfo($movie->path, PATHINFO_EXTENSION));
        $linkRel = "media/{$movie->id}.{$ext}";
        $streamPath = Storage::disk(config('movies.disk'))->exists($linkRel) ? "{$movie->id}.{$ext}" : '';

        return [
            'id' => $movie->id,
            'title' => $movie->title,
            'stream_path' => $streamPath,
            'subtitles' => $subtitles,
        ];
    }
}
