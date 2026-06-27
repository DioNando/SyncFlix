<?php

namespace Database\Seeders;

use App\Models\Movie;
use Illuminate\Database\Seeder;

class MovieSeeder extends Seeder
{
    /**
     * Enregistre le film de test (étape 1 du MVP).
     *
     * Le fichier doit exister dans storage/app/private/movies/. `updateOrCreate` rend le seed
     * idempotent (rejouable sans dupliquer).
     */
    public function run(): void
    {
        Movie::updateOrCreate(
            ['path' => 'movies/sintel.mp4'],
            ['title' => 'Sintel (trailer)'],
        );
    }
}
