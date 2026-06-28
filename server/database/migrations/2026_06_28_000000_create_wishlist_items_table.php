<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Watchlist « à regarder plus tard » : références TMDB (sans fichier), distinctes de la
     * bibliothèque (`movies`). Un item devient « disponible » si un film correspondant existe.
     */
    public function up(): void
    {
        Schema::create('wishlist_items', function (Blueprint $table) {
            $table->id();
            $table->unsignedBigInteger('tmdb_id')->unique();
            $table->string('title');
            $table->string('year')->nullable();
            $table->string('poster_path')->nullable();
            $table->text('overview')->nullable();
            $table->boolean('watched')->default(false);
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('wishlist_items');
    }
};
