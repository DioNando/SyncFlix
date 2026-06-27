<?php

namespace App\Http\Controllers;

use App\Events\VideoStateUpdated;
use App\Models\Movie;
use App\Models\WatchSession;
use Illuminate\Http\Request;

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

    private function find(string $code): WatchSession
    {
        return WatchSession::with('movie')
            ->where('code', strtoupper($code))
            ->firstOrFail();
    }

    /** Sérialise l'état partagé envoyé à l'app (et diffusé sur le WebSocket). */
    private function state(WatchSession $session, ?int $serverTimestampMs = null): array
    {
        return [
            'code' => $session->code,
            'movie' => [
                'id' => $session->movie->id,
                'title' => $session->movie->title,
            ],
            'is_playing' => $session->is_playing,
            'position_ms' => $session->current_position_ms,
            'seq' => $session->seq,
            'server_timestamp_ms' => $serverTimestampMs ?? (int) (microtime(true) * 1000),
        ];
    }
}
