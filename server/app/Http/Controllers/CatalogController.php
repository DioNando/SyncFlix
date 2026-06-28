<?php

namespace App\Http\Controllers;

use App\Models\Movie;
use App\Models\WishlistItem;
use Illuminate\Http\Request;
use Illuminate\Support\Collection;
use Illuminate\Support\Facades\Http;

/**
 * Catalogue TMDB : recherche de films + watchlist « à regarder plus tard ».
 *
 * Le serveur **proxifie** TMDB (le token v4 reste côté serveur, jamais dans l'app) et expose des
 * DTO simples. La watchlist est purement des références TMDB (sans fichier) ; un item est marqué
 * `available` si un film de la bibliothèque (`movies`) a un titre correspondant.
 */
class CatalogController extends Controller
{
    private const IMG_BASE = 'https://image.tmdb.org/t/p/w500';

    /** Recherche TMDB → liste de cartes (vide si requête vide). */
    public function search(Request $request): array
    {
        $query = trim((string) $request->query('q', ''));
        if ($query === '') {
            return [];
        }
        $data = $this->tmdb('/search/movie', ['query' => $query, 'include_adult' => 'false']);

        return collect($data['results'] ?? [])->map(fn (array $m) => $this->card($m))->all();
    }

    /** Détail d'un film TMDB (synopsis complet, durée, genres). */
    public function detail(int $tmdbId): array
    {
        $m = $this->tmdb("/movie/{$tmdbId}", []);
        abort_if(empty($m['id']), 404, 'Film introuvable sur TMDB.');

        return $this->card($m) + [
            'runtime' => $m['runtime'] ?? null,
            'genres' => collect($m['genres'] ?? [])->pluck('name')->all(),
        ];
    }

    /** Watchlist (avec flag `available` = présent dans la bibliothèque). */
    public function wishlist(): array
    {
        $titles = Movie::query()->pluck('title');

        return WishlistItem::query()
            ->orderByDesc('created_at')
            ->get()
            ->map(fn (WishlistItem $w) => $this->serializeWish($w, $titles))
            ->all();
    }

    /** Ajoute (ou met à jour) un film dans la watchlist. */
    public function store(Request $request): array
    {
        $validated = $request->validate([
            'tmdb_id' => ['required', 'integer'],
            'title' => ['required', 'string'],
            'year' => ['nullable', 'string'],
            'poster_path' => ['nullable', 'string'],
            'overview' => ['nullable', 'string'],
        ]);
        $item = WishlistItem::updateOrCreate(['tmdb_id' => $validated['tmdb_id']], $validated);

        return $this->serializeWish($item, Movie::query()->pluck('title'));
    }

    /** Coche / décoche « vu ». */
    public function update(Request $request, WishlistItem $wishlistItem): array
    {
        $validated = $request->validate(['watched' => ['required', 'boolean']]);
        $wishlistItem->update($validated);

        return $this->serializeWish($wishlistItem, Movie::query()->pluck('title'));
    }

    /** Retire un film de la watchlist. */
    public function destroy(WishlistItem $wishlistItem)
    {
        $wishlistItem->delete();

        return response()->noContent();
    }

    /** Appel TMDB authentifié (Bearer v4), langue FR par défaut. */
    private function tmdb(string $path, array $query): array
    {
        $token = config('services.tmdb.token');
        abort_if(blank($token), 500, 'TMDB_TOKEN manquant dans le .env du serveur.');

        // Sous Windows, PHP n'a souvent pas de bundle CA → cURL error 60. Si TMDB_CA_BUNDLE est
        // défini, on l'utilise (vérification TLS maintenue) ; sinon comportement par défaut.
        $caBundle = config('services.tmdb.ca_bundle');

        $response = Http::withToken($token)
            ->acceptJson()
            ->when($caBundle, fn ($http) => $http->withOptions(['verify' => $caBundle]))
            ->get('https://api.themoviedb.org/3'.$path, array_merge(['language' => 'fr-FR'], $query));

        abort_unless($response->successful(), 502, 'Service TMDB indisponible.');

        return $response->json() ?? [];
    }

    /** DTO commun (recherche + détail). `poster_path` est renvoyé pour pouvoir l'ajouter en watchlist. */
    private function card(array $m): array
    {
        $release = $m['release_date'] ?? '';

        return [
            'tmdb_id' => $m['id'],
            'title' => $m['title'] ?? ($m['name'] ?? ''),
            'year' => $release !== '' ? substr($release, 0, 4) : null,
            'poster_path' => $m['poster_path'] ?? null,
            'poster_url' => ! empty($m['poster_path']) ? self::IMG_BASE.$m['poster_path'] : null,
            'overview' => $m['overview'] ?? null,
        ];
    }

    private function serializeWish(WishlistItem $w, Collection $libraryTitles): array
    {
        return [
            'id' => $w->id,
            'tmdb_id' => $w->tmdb_id,
            'title' => $w->title,
            'year' => $w->year,
            'poster_url' => $w->poster_path ? self::IMG_BASE.$w->poster_path : null,
            'overview' => $w->overview,
            'watched' => $w->watched,
            'available' => $this->isAvailable($w->title, $libraryTitles),
        ];
    }

    /** Disponible si un titre de la bibliothèque correspond (normalisé, par inclusion souple). */
    private function isAvailable(string $title, Collection $libraryTitles): bool
    {
        $needle = $this->normalize($title);
        if ($needle === '') {
            return false;
        }

        return $libraryTitles->contains(function (string $libTitle) use ($needle) {
            $hay = $this->normalize($libTitle);

            return $hay !== '' && (str_contains($hay, $needle) || str_contains($needle, $hay));
        });
    }

    private function normalize(string $s): string
    {
        return preg_replace('/[^a-z0-9]/', '', strtolower($s)) ?? '';
    }
}
