# 0004 — Android : Room garde les faits, WorkManager garde les tâches

- Statut : accepté (M3, 2026-10-11)
- Décisions liées : D1, D2, D5, D10 ; brief §8 ; ADR 0001, 0003
- Méthode : arena pstack (`architect`). Concepteurs Opus, Sonnet, Haiku (Fable abandonné faute de
  crédits), juge Sonnet. Base retenue : le design Opus, avec greffes.

## Contexte

Un morceau doit passer de YouTube (WebView ou partage) au téléphone puis à S3, alors que Samsung tue
les applications en arrière-plan, que yt-dlp laisse des fichiers partiels, que le yt-dlp embarqué est
refusé par YouTube tant qu'il n'a pas été mis à jour (ADR 0001), et que l'écran Téléchargements doit
dire la vérité après une mort du processus.

## Décisions

| Sujet | Décision | Alternative écartée |
|---|---|---|
| État d'un morceau | Room `tracks` ne contient que des faits durables : une ligne existe seulement quand audio et miniature sont finis ; `uploadState` = `PENDING` ou `UPLOADED` | Table `downloads` avec un état « en cours » : reste bloquée sur « téléchargement » après une mort du processus |
| État d'une tâche | WorkManager (`track-{videoId}`, KEEP, backoff exponentiel, conservé 7 jours) | Recopier l'état dans Room : deux sources de vérité |
| Écran Téléchargements | Fusion à la lecture, fonction pure `downloadRows(jobs, tracks)` testée ; un fait Room terminal l'emporte sur une vieille tâche | Lire WorkInfo seul : on perd les morceaux téléchargés mais non envoyés |
| Point d'entrée | `TrackPipeline.enqueue(videoId)` pour le bouton, le partage, « Relancer » et l'enregistrement du token | Un chemin par écran |
| Moteur | Singleton `YtDlp`, un `Mutex` autour de init / mise à jour / téléchargement ; jamais de téléchargement avant une première mise à jour ; mise à jour au lancement au plus toutes les 24 h | Mise à jour dans chaque worker : courses sur les fichiers de yt-dlp |
| Fichiers partiels | yt-dlp écrit dans un dossier temporaire et donne lui-même le chemin final (`--print after_move:`) ; reprise = retéléchargement (`--force-overwrites`) | Lister le dossier : un `.part` pourrait passer pour fini |
| Token | Tink AES256-GCM, clé dans le Keystore, valeur jamais affichée ni journalisée ; vérifié par `GET /api/auth/me` | EncryptedSharedPreferences (déprécié) |
| Réseau | Client serveur reconstruit à chaque appel depuis les réglages, seul porteur du Bearer ; les PUT S3 passent par un client sans en-tête d'auth | Un client global avec intercepteur : le token pourrait partir vers S3 |
| Contrat | DTO écrits à la main + test de contrat contre `server/openapi.json` | Génération de code : outillage lourd pour 3 appels |
| Hors M3 | Pas de suppression de morceau (attend l'outbox de M5), pas d'import de playlist, pas de Shorts, pas de « dernière synchro » | — |

## Conséquences

- Un échec de téléchargement disparaît de l'écran après 7 jours (rétention WorkManager). Un envoi en échec
  reste visible tant que la ligne Room est `PENDING`.
- Les téléchargements sont faits un par un : sans effet pour un morceau, à surveiller pour les playlists (M5).
- L'écran de debug de M0 a été supprimé dès M3 (décidé avec l'utilisateur), pour n'avoir qu'un seul
  propriétaire du moteur.
- Avant la page web (M6), le token d'appareil se crée par `curl` sur `POST /api/devices`.
