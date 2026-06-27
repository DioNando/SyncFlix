<?php

return [

    /*
    |--------------------------------------------------------------------------
    | Bibliothèque de films
    |--------------------------------------------------------------------------
    |
    | Le serveur découvre les films en scannant un dossier (commande `movies:scan`).
    | Dépose tes fichiers vidéo dans `storage/app/private/{directory}` puis lance le scan.
    | Les sous-titres sont des fichiers « sidecar » partageant le nom de la vidéo, suffixés
    | par la langue : `sintel.fr.vtt`, `sintel.en.srt` à côté de `sintel.mp4`.
    |
    */

    'disk' => env('MOVIES_DISK', 'local'),

    'directory' => env('MOVIES_DIRECTORY', 'movies'),

    'video_extensions' => ['mp4', 'mkv', 'webm', 'm4v', 'mov'],

    // Extensions de sous-titres → type MIME attendu par ExoPlayer (Media3).
    'subtitle_mimes' => [
        'vtt' => 'text/vtt',
        'srt' => 'application/x-subrip',
    ],

    // Étiquettes lisibles par code langue (fallback = code en majuscules).
    'languages' => [
        'fr' => 'Français',
        'en' => 'English',
        'es' => 'Español',
        'de' => 'Deutsch',
        'it' => 'Italiano',
        'pt' => 'Português',
        'ar' => 'العربية',
    ],

];
