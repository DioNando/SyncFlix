<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Sessions de visionnage partagées (cf. ARCHITECTURE.md).
     *
     * Nommée `watch_sessions` (et non `sessions`) pour ne pas entrer en collision avec la table
     * `sessions` du framework (driver de session HTTP).
     *
     * État partagé autoritatif : le serveur détient `is_playing` / `current_position_ms` / `seq`
     * (incrémenté à chaque mise à jour) ; le timestamp serveur est estampillé à la volée au broadcast.
     */
    public function up(): void
    {
        Schema::create('watch_sessions', function (Blueprint $table) {
            $table->id();
            $table->string('code')->unique();              // code court d'appairage (ex. ABC123)
            $table->foreignId('movie_id')->constrained();  // film regardé
            $table->unsignedBigInteger('current_position_ms')->default(0);
            $table->boolean('is_playing')->default(false);
            $table->unsignedBigInteger('seq')->default(0);  // n° de séquence croissant (anti-désordre)
            $table->timestamps();
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('watch_sessions');
    }
};
