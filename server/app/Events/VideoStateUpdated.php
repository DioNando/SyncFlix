<?php

namespace App\Events;

use App\Models\WatchSession;
use Illuminate\Broadcasting\Channel;
use Illuminate\Broadcasting\InteractsWithSockets;
use Illuminate\Contracts\Broadcasting\ShouldBroadcastNow;
use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

/**
 * Diffusé à chaque changement d'état de lecture d'une session (Play / Pause / Seek).
 *
 * `ShouldBroadcastNow` → diffusion **synchrone** (pas besoin d'un worker de queue en dev).
 *
 * Canal **public** `movie-session.{code}` : l'app n'a pas de comptes, le code court fait office de
 * secret d'appairage. L'anti-boucle se fait côté client via `triggered_by` (cf. ARCHITECTURE.md).
 * Durcissement possible plus tard : canal privé + auth par code.
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

    public function broadcastOn(): Channel
    {
        return new Channel("movie-session.{$this->session->code}");
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
