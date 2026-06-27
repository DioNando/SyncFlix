<?php

namespace App\Events;

use App\Models\WatchSession;
use Illuminate\Broadcasting\InteractsWithSockets;
use Illuminate\Broadcasting\PresenceChannel;
use Illuminate\Contracts\Broadcasting\ShouldBroadcastNow;
use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

/**
 * Réaction emoji éphémère diffusée pendant le visionnage (pas de persistance).
 *
 * Diffusée à tous les abonnés du canal de présence (l'émetteur voit aussi sa propre réaction
 * « flotter » → expérience plus vivante ; pas d'anti-boucle nécessaire ici).
 */
class ReactionSent implements ShouldBroadcastNow
{
    use Dispatchable, InteractsWithSockets, SerializesModels;

    public function __construct(
        public WatchSession $session,
        public string $emoji,
    ) {}

    public function broadcastOn(): PresenceChannel
    {
        return new PresenceChannel("movie-session.{$this->session->code}");
    }

    public function broadcastAs(): string
    {
        return 'ReactionSent';
    }

    public function broadcastWith(): array
    {
        return ['emoji' => $this->emoji];
    }
}
