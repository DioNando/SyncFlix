<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    /**
     * Pistes de sous-titres « sidecar » découvertes au scan (cf. config/movies.php).
     *
     * Stockées en JSON sur le film : `[{lang, label, path, mime}]`. Servies par
     * `GET /api/movies/{movie}/subtitles/{index}` (l'index = la position dans ce tableau).
     */
    public function up(): void
    {
        Schema::table('movies', function (Blueprint $table) {
            $table->json('subtitles')->nullable()->after('path');
        });
    }

    public function down(): void
    {
        Schema::table('movies', function (Blueprint $table) {
            $table->dropColumn('subtitles');
        });
    }
};
