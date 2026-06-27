<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\HasMany;

/**
 * Un film disponible au visionnage.
 *
 * @property string     $title      Titre affiché.
 * @property string     $path       Chemin relatif du fichier dans le disque `local` (ex. `movies/sintel.mp4`).
 * @property array|null $subtitles  Pistes sidecar `[{lang, label, path, mime}]` (cf. movies:scan).
 */
class Movie extends Model
{
    protected $fillable = ['title', 'path', 'subtitles'];

    protected $casts = [
        'subtitles' => 'array',
    ];

    /** Sessions de visionnage portant sur ce film (sert au scan : ne pas purger un film utilisé). */
    public function watchSessions(): HasMany
    {
        return $this->hasMany(WatchSession::class);
    }
}
