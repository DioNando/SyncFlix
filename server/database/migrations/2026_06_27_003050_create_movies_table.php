<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Table des films (cf. ARCHITECTURE.md).
     *
     * `path` : chemin RELATIF du fichier vidéo dans le disque `local` (storage/app/private),
     * ex. `movies/sintel.mp4`. Le streaming résout ce chemin en absolu (cf. StreamController).
     */
    public function up(): void
    {
        Schema::create('movies', function (Blueprint $table) {
            $table->id();
            $table->string('title');
            $table->string('path');
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('movies');
    }
};
