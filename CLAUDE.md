# FreeTube

App Android (YouTube → audio → S3 + lecteur) et site web perso. **Source de vérité : `docs/BRIEF.md`**. Le lire avant toute action. Ses décisions sont actées : signaler un problème plutôt que contourner.

## Reprendre le projet

1. Lire `docs/BRIEF.md`, puis la dernière entrée de `docs/LEARNING.md` (jalon terminé) et `git log --oneline -20`.
2. Les jalons sont en section 11 du brief. Un jalon à la fois, puis stop et attente de validation.

## Paramètres actés

- Package Android : `fr.appartfinder.freetube`
- Bucket S3 : `freetube-7k3q9x` (eu-west-3)
- DNS d'`appartfinder.fr` : OVH (registrar externe, pas Route 53)
- Téléphone de test : Samsung Galaxy A06, Android 15, arm64-v8a

## Règles de travail

- Français pour les échanges, la doc et les textes d'UI. Anglais pour le code, les identifiants, les commits et les noms de fichiers.
- Expliquer le pourquoi, les alternatives écartées et le compromis de chaque décision non triviale.
- **Jamais sans accord explicite** : commande AWS, DNS, `ssh freetube` qui modifie le serveur, suppression de données. La console AWS, c'est l'utilisateur qui la manipule ; Claude écrit la config et le guide.
- Pendant un test manuel sur le téléphone : aucune commande `adb` qui modifie l'app (`install`, `am`, `pm`) avant que l'utilisateur ait confirmé la fin du test. Une réinstallation tue le processus et coupe la lecture.
- Par jalon : plan (pstack) → `/ponytail` → implémentation → tests + lint → `/thermo-nuclear-code-quality-review` → ADR (`docs/adr/NNNN-titre.md`) + récap dans `docs/LEARNING.md` → commits → stop.
- Le brief l'emporte sur les skills : ponytail ne retire jamais sécurité, validation, gestion d'erreurs ni tests.
- Conventional commits, petits, en anglais. Jamais de secrets (`.env`, clés, tokens, `.pem`).
- **Aucune attribution à Claude** dans les commits et les PR : pas de `Co-Authored-By`, pas de mention « Generated with Claude Code ». Claude ne doit pas apparaître dans les contributeurs GitHub.
- Pas de dépendance sans justification. Pas d'abstraction spéculative.

## Commandes

| Zone | Commande |
|---|---|
| Serveur (`server/`, uv) | `uv run pytest` · `uv run ruff check` · `uv run ruff format --check` · contrat API : `uv run python -m freetube.export_openapi` (à relancer et commiter après tout changement d'API) |
| Web (`web/`) | `npm run gen:api` · `npx tsc --noEmit` · `npx eslint .` |
| Android (`android/`) | `export JAVA_HOME=/opt/android-studio/jbr` (le Java 11 système est trop vieux) · `./gradlew testDebugUnitTest lint` · `./gradlew assembleDebug` · `adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk` |
| Image (`server/`) | `docker build --platform linux/arm64 .` (la prod est en arm64) |
| Tâches serveur | `python -m freetube.hash_password` · `python -m freetube.backup [--restore KEY]` · `python -m freetube.purge` (dans le conteneur, voir `docs/deploy.md`) |
| Token d'appareil (avant M6) | `docs/deploy.md`, section « Créer un token d'appareil » |
| Prod | `ssh freetube` (lecture seule sans accord) · déployer : `docs/deploy.md` · console AWS : `docs/infra-setup.md` |

Mettre ce tableau à jour quand une commande change.
