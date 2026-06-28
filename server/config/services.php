<?php

return [

    /*
    |--------------------------------------------------------------------------
    | Third Party Services
    |--------------------------------------------------------------------------
    |
    | This file is for storing the credentials for third party services such
    | as Mailgun, Postmark, AWS and more. This file provides the de facto
    | location for this type of information, allowing packages to have
    | a conventional file to locate the various service credentials.
    |
    */

    'postmark' => [
        'key' => env('POSTMARK_API_KEY'),
    ],

    'resend' => [
        'key' => env('RESEND_API_KEY'),
    ],

    'ses' => [
        'key' => env('AWS_ACCESS_KEY_ID'),
        'secret' => env('AWS_SECRET_ACCESS_KEY'),
        'region' => env('AWS_DEFAULT_REGION', 'us-east-1'),
    ],

    'slack' => [
        'notifications' => [
            'bot_user_oauth_token' => env('SLACK_BOT_USER_OAUTH_TOKEN'),
            'channel' => env('SLACK_BOT_USER_DEFAULT_CHANNEL'),
        ],
    ],

    // TMDB : recherche de films + watchlist (cf. CatalogController). Token v4 « read access »
    // (Bearer), à mettre dans .env : TMDB_TOKEN=eyJhbGciOi...
    'tmdb' => [
        'token' => env('TMDB_TOKEN'),
        // Optionnel (Windows) : chemin vers un bundle CA si PHP n'en a pas de configuré dans php.ini
        // → corrige « cURL error 60: SSL certificate problem ». Ex. TMDB_CA_BUNDLE="C:\php\extras\ssl\cacert.pem".
        'ca_bundle' => env('TMDB_CA_BUNDLE'),
    ],

];
