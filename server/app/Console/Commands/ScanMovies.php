<?php

namespace App\Console\Commands;

use App\Models\Movie;
use App\Models\WatchSession;
use Illuminate\Console\Command;
use Illuminate\Support\Facades\Storage;
use Illuminate\Support\Str;

/**
 * Scanne le dossier de films (cf. config/movies.php) et (re)peuple la table `movies`.
 *
 * - Titre = nom du fichier sans extension, « humanisé » (underscores/tirets → espaces).
 * - Sous-titres = fichiers sidecar partageant le nom de base, suffixés par la langue :
 *   `sintel.fr.vtt`, `sintel.en.srt` à côté de `sintel.mp4`.
 *
 * Idempotent (`updateOrCreate` sur le chemin) : rejouable à volonté après ajout de fichiers.
 */
class ScanMovies extends Command
{
    protected $signature = 'movies:scan';

    protected $description = 'Découvre les films du dossier configuré et met à jour la bibliothèque.';

    public function handle(): int
    {
        $disk = Storage::disk(config('movies.disk'));
        $dir = trim(config('movies.directory'), '/');
        $videoExt = config('movies.video_extensions');
        $subMimes = config('movies.subtitle_mimes');
        $languages = config('movies.languages');

        if (! $disk->exists($dir)) {
            $this->warn("Dossier introuvable : {$dir} (sur le disque ".config('movies.disk').').');

            return self::FAILURE;
        }

        $files = $disk->files($dir);

        // Index des sous-titres par nom de base de vidéo : base => [{lang,label,path,mime}].
        $subtitlesByBase = [];
        foreach ($files as $file) {
            $ext = strtolower(pathinfo($file, PATHINFO_EXTENSION));
            if (! isset($subMimes[$ext])) {
                continue;
            }
            // `sintel.fr` → base `sintel`, langue `fr`. On ne découpe la langue que si le dernier
            // segment EST un code de langue plausible (connu, ou 2-3 lettres) ; sinon un nom de film
            // à points comme `the.matrix` serait pris pour la langue « matrix » (faux appariement).
            $name = pathinfo($file, PATHINFO_FILENAME); // ex. sintel.fr / the.matrix
            $lang = 'und';
            $base = $name;
            if (Str::contains($name, '.')) {
                $candidate = Str::afterLast($name, '.');
                if (isset($languages[$candidate]) || preg_match('/^[a-z]{2,3}$/', $candidate)) {
                    $lang = $candidate;
                    $base = Str::beforeLast($name, '.');
                }
            }
            $subtitlesByBase[$base][] = [
                'lang' => $lang,
                'label' => $languages[$lang] ?? strtoupper($lang),
                'path' => $file,
                'mime' => $subMimes[$ext],
            ];
        }

        // Liens « propres » id.ext servis par Caddy (URL sans caractères spéciaux → fini les 404
        // sur les crochets/parenthèses des noms de release).
        $disk->makeDirectory('media');
        $validLinks = [];

        $seen = [];
        $count = 0;
        foreach ($files as $file) {
            $ext = strtolower(pathinfo($file, PATHINFO_EXTENSION));
            if (! in_array($ext, $videoExt, true)) {
                continue;
            }

            $base = pathinfo($file, PATHINFO_FILENAME);
            $title = $this->prettyTitle($base);
            $subtitles = $subtitlesByBase[$base] ?? [];

            $movie = Movie::updateOrCreate(
                ['path' => $file],
                ['title' => $title, 'subtitles' => $subtitles],
            );
            $seen[] = $file;
            $count++;

            $linkName = "media/{$movie->id}.{$ext}";
            $this->ensureMediaLink($disk, $file, $linkName);
            $validLinks[] = "{$movie->id}.{$ext}";

            $this->line("  ✓ {$title}".(count($subtitles) ? ' ('.count($subtitles).' sous-titre(s))' : ''));
        }

        // Purge les liens devenus orphelins (films retirés / renommés).
        foreach ($disk->files('media') as $link) {
            if (! in_array(basename($link), $validLinks, true)) {
                $disk->delete($link);
            }
        }

        // Purge les films dont le fichier a disparu — seulement si on a bien vu des vidéos (sinon un
        // dossier vide/mal configuré viderait toute la bibliothèque). Les sessions qui référencent ces
        // films sont devenues caduques → on les supprime D'ABORD (sinon la clé étrangère RESTRICT
        // empêcherait la suppression du film, et le film resterait visible dans la liste).
        $removed = 0;
        if (! empty($seen)) {
            $staleIds = Movie::whereNotIn('path', $seen)->pluck('id');
            if ($staleIds->isNotEmpty()) {
                WatchSession::whereIn('movie_id', $staleIds)->delete();
                $removed = Movie::whereIn('id', $staleIds)->delete();
            }
        }

        $this->info("Scan terminé : {$count} film(s) indexé(s)".($removed ? ", {$removed} retiré(s)." : '.'));

        return self::SUCCESS;
    }

    /**
     * Crée (si absent) un lien `media/{id}.{ext}` vers le vrai fichier vidéo, pour que Caddy serve
     * via une URL sans caractères spéciaux. Hardlink d'abord (NTFS/ext4, sans privilège) ; repli
     * symlink ; sinon avertissement (le film basculera sur le streaming PHP de secours).
     */
    private function ensureMediaLink($disk, string $file, string $linkRel): void
    {
        $linkAbs = $disk->path($linkRel);
        if (file_exists($linkAbs)) {
            return;
        }
        $targetAbs = $disk->path($file);
        if (@link($targetAbs, $linkAbs) || @symlink($targetAbs, $linkAbs)) {
            return;
        }
        $this->warn("  ⚠ Lien /media impossible pour {$file} → repli streaming PHP (mono-thread).");
    }

    /**
     * Transforme un nom de fichier « scène » en titre lisible.
     *
     * Ex. `House of the Dragon S03E01 MULTI 1080p WEB H264-HiggsBoson` → `House Of The Dragon S03E01`,
     * `Inception.2010.1080p.BluRay.x264-AMIABLE` → `Inception 2010`.
     *
     * Heuristique : on sépare en jetons (`. _` → espace), on coupe au 1er jeton **technique**
     * (résolution/source/codec/langue) et juste après un marqueur d'épisode `SxxExx`. Les groupes de
     * release (ex. `-HiggsBoson`) viennent après les tags, donc déjà retirés par la coupure ; on ne
     * les strip PAS explicitement pour ne pas mutiler un titre composé (« X-Men »). Repli si vide.
     */
    private function prettyTitle(string $base): string
    {
        // Garde `-` (groupes de release / noms composés) ; n'éclate que sur `.` `_` et espaces.
        $name = trim(preg_replace('/[\s._]+/', ' ', $base));

        // Jetons « techniques » qui marquent la fin du vrai titre.
        $junk = '/^(480p|576p|720p|1080p|2160p|4k|web|web-?dl|web-?rip|webrip|bluray|blu-ray|brrip|bdrip|'
            .'hdrip|hdtv|dvdrip|dvd|remux|x264|x265|h\.?264|h\.?265|hevc|avc|xvid|divx|aac|ac3|eac3|'
            .'dd5\.?1|dts|truehd|atmos|10bit|hdr|sdr|multi|vff|vf|vo|vostfr|french|truefrench|'
            .'repack|proper|internal|amzn|nf|dsnp|hmax)$/i';

        $kept = [];
        foreach (explode(' ', $name) as $tok) {
            if ($tok === '') {
                continue;
            }
            if (preg_match($junk, $tok)) {
                break; // 1er jeton technique → on arrête
            }
            $kept[] = $tok;
            if (preg_match('/^S\d{1,2}E\d{1,2}$/i', $tok)) {
                break; // on garde jusqu'au marqueur d'épisode inclus
            }
        }

        $title = trim(implode(' ', $kept));
        if ($title === '') {
            $title = $name; // garde-fou : jamais de titre vide
        }

        // Title Case, puis on remet les marqueurs d'épisode en majuscules (S03e01 → S03E01).
        $title = Str::of($title)->squish()->title()->value();

        return preg_replace_callback(
            '/\bS(\d{1,2})E(\d{1,2})\b/i',
            fn ($m) => 'S'.$m[1].'E'.$m[2],
            $title,
        );
    }
}
