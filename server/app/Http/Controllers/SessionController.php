<?php

namespace App\Http\Controllers;

use App\Events\ReactionSent;
use App\Events\VideoStateUpdated;
use App\Models\Movie;
use App\Models\WatchSession;
use Illuminate\Http\Request;
use Illuminate\Support\Str;

/**
 * Gestion des sessions de visionnage (état partagé autoritatif — cf. ARCHITECTURE.md).
 *
 * Le serveur est la source de vérité : il détient `is_playing` / `position_ms` / `seq` et
 * estampille `server_timestamp_ms` à chaque réponse. Le broadcast temps réel (Reverb) sera
 * branché sur `update` à l'étape 3.
 */
class SessionController extends Controller
{
    /** Crée une session (sur un film donné, sinon le premier disponible) et renvoie son état. */
    public function create(Request $request)
    {
        $movieId = $request->integer('movie_id') ?: Movie::query()->value('id');
        abort_if($movieId === null, 422, 'Aucun film disponible.');
        abort_unless(Movie::whereKey($movieId)->exists(), 422, 'Film introuvable.');

        $session = WatchSession::create([
            'code' => WatchSession::generateCode(),
            'movie_id' => $movieId,
            'current_position_ms' => 0,
            'is_playing' => false,
            'seq' => 0,
        ]);

        return $this->state($session->load('movie'));
    }

    /** Rejoint une session existante via son code et renvoie l'état courant (pour se caler). */
    public function join(string $code)
    {
        return $this->state($this->find($code));
    }

    /** État courant de la session (resync à la reconnexion). */
    public function show(string $code)
    {
        return $this->state($this->find($code));
    }

    /**
     * Met à jour l'intention de lecture (Play/Pause/Seek). Le serveur incrémente `seq`, persiste
     * l'état, puis renverra (étape 3) cet état sur le canal privé Reverb.
     */
    public function update(Request $request, string $code)
    {
        $validated = $request->validate([
            'is_playing' => ['required', 'boolean'],
            'position_ms' => ['required', 'integer', 'min:0'],
            'triggered_by' => ['nullable', 'string', 'max:64'],
        ]);

        $session = $this->find($code);
        $session->update([
            'is_playing' => $validated['is_playing'],
            'current_position_ms' => $validated['position_ms'],
            'seq' => $session->seq + 1,
        ]);

        // Diffuse à tous les abonnés du canal (y compris l'émetteur) : le client ignore son propre
        // événement via `triggered_by` (anti-boucle, cf. ARCHITECTURE.md).
        $event = new VideoStateUpdated($session, $validated['triggered_by'] ?? null);
        broadcast($event);

        // Réponse alignée sur le payload diffusé (même seq + même horodatage serveur).
        return $this->state($session->load('movie'), $event->serverTimestampMs);
    }

    /** Horloge serveur (epoch ms) — sert au ClockSync HTTP côté mobile (offset d'horloge). */
    public function time(): array
    {
        return ['server_timestamp_ms' => (int) (microtime(true) * 1000)];
    }

    /** Diffuse une réaction emoji éphémère à tous les spectateurs (pas de persistance). */
    public function react(Request $request, string $code)
    {
        $validated = $request->validate([
            'emoji' => ['required', 'string', 'max:16'],
        ]);

        $session = $this->find($code);
        broadcast(new ReactionSent($session, $validated['emoji']));

        return response()->noContent();
    }

    /**
     * Auth d'abonnement au canal **de présence** Reverb (protocole Pusher).
     *
     * L'app n'a pas de comptes : au lieu de l'auth web/cookie de Laravel, on signe ici à la main
     * dès lors que le code de session existe. Le secret Reverb reste côté serveur.
     *
     * - Présence (`presence-…`) : la signature couvre `socket_id:channel:channel_data` et la réponse
     *   renvoie aussi `channel_data` (identité = `user_id` fourni par le client, anonyme). Sert au
     *   compteur de spectateurs (salon d'attente) et aux réactions.
     * - Privé (`private-…`) conservé par compatibilité : signature `socket_id:channel`.
     */
    public function authChannel(Request $request)
    {
        $validated = $request->validate([
            'socket_id' => ['required', 'string'],
            'channel_name' => ['required', 'string'],
            'user_id' => ['nullable', 'string', 'max:64'],
        ]);

        $channel = $validated['channel_name'];
        $secret = config('broadcasting.connections.reverb.secret');
        $key = config('broadcasting.connections.reverb.key');

        if (preg_match('/^presence-movie-session\.([A-Z0-9]+)$/', $channel, $m) === 1) {
            WatchSession::where('code', $m[1])->firstOrFail();
            $userId = $validated['user_id'] ?: (string) Str::uuid();
            // `user_info` vide : salon « minimal » anonyme (pas de pseudo), on ne compte que les membres.
            $channelData = json_encode(['user_id' => $userId, 'user_info' => (object) []]);
            $signature = hash_hmac('sha256', $validated['socket_id'].':'.$channel.':'.$channelData, $secret);

            return ['auth' => "{$key}:{$signature}", 'channel_data' => $channelData];
        }

        if (preg_match('/^private-movie-session\.([A-Z0-9]+)$/', $channel, $m) === 1) {
            WatchSession::where('code', $m[1])->firstOrFail();
            $signature = hash_hmac('sha256', $validated['socket_id'].':'.$channel, $secret);

            return ['auth' => "{$key}:{$signature}"];
        }

        abort(403, 'Canal non autorisé.');
    }

    private function find(string $code): WatchSession
    {
        $session = WatchSession::with('movie')
            ->where('code', strtoupper($code))
            ->firstOrFail();

        // Le film a pu disparaître (purge) : éviter un TypeError dans state() (movie non-nullable).
        abort_if($session->movie === null, 410, 'Le film de cette session n\'est plus disponible.');

        return $session;
    }

    /** Sérialise l'état partagé envoyé à l'app (film + sous-titres + état de lecture). */
    private function state(WatchSession $session, ?int $serverTimestampMs = null): array
    {
        return [
            'code' => $session->code,
            'movie' => MovieController::serialize($session->movie),
            'is_playing' => $session->is_playing,
            'position_ms' => $session->current_position_ms,
            'seq' => $session->seq,
            'server_timestamp_ms' => $serverTimestampMs ?? (int) (microtime(true) * 1000),
        ];
    }
}
