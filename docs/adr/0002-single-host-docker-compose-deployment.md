# 0002 — Déploiement sur une seule instance avec Docker Compose et Caddy

- Statut : accepté (M1, 2026-10-10)
- Décisions liées : D4 (un seul processus), D8 (Caddy + Compose), D9 (rôle IAM, pas de clés)

## Contexte

Un seul utilisateur, quelques centaines de morceaux. Le serveur ne fait que du JSON, puisque l'audio passe
directement entre S3 et les clients (D3). Il faut du HTTPS, des déploiements reproductibles et un minimum
d'entretien.

## Décisions et alternatives écartées

| Sujet | Choix | Écarté | Pourquoi |
|---|---|---|---|
| Orchestration | Docker Compose sur l'instance | ECS, Kubernetes | Démesuré pour un conteneur applicatif |
| HTTPS | Caddy (certificat automatique) | Nginx + certbot, ALB | Une ligne de config. L'ALB coûte environ 20 $/mois |
| Installation de Docker | Paquets Ubuntu (`docker.io`, `docker-compose-v2`, `docker-buildx`) | Dépôt de Docker Inc. | Correctifs automatiques via `unattended-upgrades` ; versions un peu plus anciennes |
| Base SQLite | Dossier de l'hôte `/srv/freetube/data` | Volume Docker nommé | Le script de sauvegarde (sur l'hôte) lit la base directement |
| Logs | Pilote Docker `local` | `json-file` (défaut) | `json-file` ne fait aucune rotation et remplirait les 20 Gio du disque |
| Identité AWS | Rôle d'instance via IMDSv2 (hop limit 2) | Clés dans `.env` | Pas de secret à stocker ni à faire tourner (D9) |
| Utilisateur du conteneur | Non-root, uid 1000 | root | Limite les dégâts d'une faille applicative |
| Proxy headers | `--forwarded-allow-ips *` | Liste d'IP | Seul Caddy atteint `api` (pas de port publié) ; requis pour connaître l'IP réelle (rate-limit du login, M2) |

## Conséquences

- Un point unique de panne : acceptable pour un usage perso. On s'en protège par les snapshots EBS
  quotidiens et la sauvegarde SQLite sur S3.
- Le build de l'image se fait sur l'instance (`up --build`) : pas de registre d'images à gérer, mais la
  construction utilise la RAM de la t4g.small (2 Gio). À surveiller en M6, quand le build Node s'ajoutera.
- Le contexte de build est `server/`. En M6, il passera à la racine du dépôt pour inclure `web/`.
