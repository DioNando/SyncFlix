<?php

namespace App\Events;

use App\Models\WatchSession;
use Illuminate\Broadcasting\InteractsWithSockets;
use Illuminate\Broadcasting\PresenceChannel;
use Illuminate\Contracts\Broadcasting\ShouldBroadcastNow;
use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

/**
 * Diffusé à chaque changement d'état de lecture d'une session (Play / Pause / Seek).
 *
 * `ShouldBroadcastNow` → diffusion **synchrone** (pas besoin d'un worker de queue en dev).
 *
 * Canal de **présence** `presence-movie-session.{code}` : l'abonnement exige une signature délivrée
 * par `POST /api/broadcasting/auth` (ne signe que si le code de session existe), et le canal sert
 * aussi à compter les spectateurs connectés (gate du salon d'attente) + porte les réactions emoji.
 * L'anti-boucle se fait côté client via `triggered_by`.
 */
class VideoStateUpdated implements ShouldBroadcastNow
{
    use Dispatchable, InteractsWithSockets, SerializesModels;

    public int $serverTimestampMs;

    public function __construct(
        public WatchSession $session,
        public ?string $triggeredBy = null,
        ?int $serverTimestampMs = null,
    ) {
        $this->serverTimestampMs = $serverTimestampMs ?? (int) (microtime(true) * 1000);
    }

    public function broadcastOn(): PresenceChannel
    {
        // PresenceChannel préfixe automatiquement `presence-` → `presence-movie-session.{code}`.
        return new PresenceChannel("movie-session.{$this->session->code}");
    }

    /** Nom d'événement lisible côté client (sinon Laravel diffuse le FQCN). */
    public function broadcastAs(): string
    {
        return 'VideoStateUpdated';
    }

    /** Charge utile = contrat de l'état partagé (cf. ARCHITECTURE.md). */
    public function broadcastWith(): array
    {
        return [
            'is_playing' => $this->session->is_playing,
            'position_ms' => $this->session->current_position_ms,
            'seq' => $this->session->seq,
            'server_timestamp_ms' => $this->serverTimestampMs,
            'triggered_by' => $this->triggeredBy,
        ];
    }
}
