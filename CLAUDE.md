# FreeTube

App Android (YouTube → audio → S3 + lecteur) et site web perso. **Source de vérité : `docs/BRIEF.md`**. Le lire avant toute action. Ses décisions sont actées : signaler un problème plutôt que contourner.

## Reprendre le projet

1. Lire `docs/BRIEF.md`, puis la dernière entrée de `docs/LEARNING.md` (jalon terminé) et `git log --oneline -20`.
2. Les jalons sont en section 11 du brief. Un jalon à la fois, puis stop et attente de validation.

## Règles de travail

- Français pour les échanges, la doc et les textes d'UI. Anglais pour le code, les identifiants, les commits et les noms de fichiers.
- Expliquer le pourquoi, les alternatives écartées et le compromis de chaque décision non triviale.
- **Jamais sans accord explicite** : commande AWS, DNS, `ssh freetube` qui modifie le serveur, suppression de données. La console AWS, c'est l'utilisateur qui la manipule ; Claude écrit la config et le guide.
- Par jalon : plan (pstack) → `/ponytail` → implémentation → tests + lint → `/thermo-nuclear-code-quality-review` → ADR (`docs/adr/NNNN-titre.md`) + récap dans `docs/LEARNING.md` → commits → stop.
- Le brief l'emporte sur les skills : ponytail ne retire jamais sécurité, validation, gestion d'erreurs ni tests.
- Conventional commits, petits, en anglais. Jamais de secrets (`.env`, clés, tokens, `.pem`).
- Pas de dépendance sans justification. Pas d'abstraction spéculative.

## Commandes

| Zone | Commande |
|---|---|
| Serveur (`server/`, uv) | `uv run pytest` · `uv run ruff check` · `uv run ruff format --check` |
| Web (`web/`) | `npm run gen:api` · `npx tsc --noEmit` · `npx eslint .` |
| Android (`android/`) | `./gradlew lint test` · `./gradlew assembleDebug` |
| Prod | `ssh freetube` (lecture seule sans accord) · voir `docs/deploy.md` |

Mettre ce tableau à jour quand une commande change.
