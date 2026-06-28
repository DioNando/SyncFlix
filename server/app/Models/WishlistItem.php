<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;

/**
 * Un film « à regarder plus tard » (référence TMDB, pas de fichier). Cf. CatalogController.
 *
 * @property int         $tmdb_id
 * @property string      $title
 * @property string|null $year
 * @property string|null $poster_path  Chemin TMDB (ex. `/abc.jpg`) ; l'URL est reconstruite à l'expo.
 * @property string|null $overview
 * @property bool        $watched
 */
class WishlistItem extends Model
{
    protected $fillable = ['tmdb_id', 'title', 'year', 'poster_path', 'overview', 'watched'];

    protected $casts = [
        'tmdb_id' => 'integer',
        'watched' => 'boolean',
    ];
}
