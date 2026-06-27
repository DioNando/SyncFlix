<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

/**
 * Un film disponible au visionnage.
 *
 * @property string $title  Titre affiché.
 * @property string $path   Chemin relatif du fichier dans le disque `local` (ex. `movies/sintel.mp4`).
 */
class Movie extends Model
{
    protected $fillable = ['title', 'path'];
}
