<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/**
 * Une session de visionnage partagée entre deux téléphones.
 *
 * @property string $code
 * @property int    $movie_id
 * @property int    $current_position_ms
 * @property bool   $is_playing
 * @property int    $seq
 */
class WatchSession extends Model
{
    protected $fillable = [
        'code', 'movie_id', 'current_position_ms', 'is_playing', 'seq',
    ];

    protected $casts = [
        'is_playing' => 'boolean',
        'current_position_ms' => 'integer',
        'seq' => 'integer',
    ];

    public function movie(): BelongsTo
    {
        return $this->belongsTo(Movie::class);
    }

    /**
     * Génère un code d'appairage court, lisible et unique (sans caractères ambigus 0/O/1/I).
     */
    public static function generateCode(int $length = 6): string
    {
        $alphabet = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
        do {
            $code = '';
            for ($i = 0; $i < $length; $i++) {
                $code .= $alphabet[random_int(0, strlen($alphabet) - 1)];
            }
        } while (static::where('code', $code)->exists());

        return $code;
    }
}
