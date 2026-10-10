# Installation de l'infra AWS — guide pas à pas

Région : **eu-west-3 (Paris)** partout. Vérifie-la en haut à droite de la console avant chaque étape.
Fais les étapes dans l'ordre. Les étapes 1 à 3 ne cassent rien. **L'étape 4 coupe l'ancien site.**
L'étape 8 se fait seulement en M7.

Valeurs du projet :

| Élément | Valeur |
|---|---|
| Bucket | `freetube-7k3q9x` |
| Rôle IAM | `freetube-ec2` |
| Politique IAM | `freetube-s3` (contenu : `infra/iam/freetube-s3-policy.json`) |
| Instance | `freetube`, IP élastique `15.237.133.3` |

---

## 1. Bucket S3

**Pourquoi ces réglages :** le bucket contient toute ta musique et les sauvegardes. Il est privé : on n'y
accède que par des URLs pré-signées que génère le serveur. Le versioning protège contre une suppression
par erreur pendant 30 jours.

1. Console → **S3** → **Créer un compartiment**.
2. Type : *Usage général*. Nom : `freetube-7k3q9x`.
3. **Propriété des objets** : *ACL désactivées* (recommandé).
4. **Bloquer l'accès public** : laisse **les 4 cases cochées** (« Bloquer *tous* les accès publics »).
5. **Gestion des versions** : *Activer*.
6. **Chiffrement par défaut** : *SSE-S3* (clés gérées par Amazon S3). Clé de compartiment : *Activer*.
7. **Créer le compartiment**.

### Règles de cycle de vie (`infra/s3/lifecycle.json`)

Méthode la plus sûre : **CloudShell**. C'est un terminal dans le navigateur, déjà connecté à ton compte :
icône `>_` en haut de la console.

1. Dans CloudShell : **Actions** → **Charger un fichier** → choisis `infra/s3/lifecycle.json`.
2. Lance :
   ```bash
   aws s3api put-bucket-lifecycle-configuration --bucket freetube-7k3q9x --lifecycle-configuration file://lifecycle.json
   aws s3api get-bucket-lifecycle-configuration --bucket freetube-7k3q9x
   ```
3. La deuxième commande doit afficher 3 règles.

Ce que font les 3 règles :

| Règle | Effet |
|---|---|
| `expire-noncurrent-versions` | Une version remplacée ou supprimée disparaît définitivement 30 jours plus tard. Nettoie aussi les « marqueurs de suppression » orphelins |
| `expire-backups` | Les sauvegardes de plus de 30 jours passent en version non courante, puis disparaissent 30 jours après (règle 1). Une sauvegarde vit donc au plus ~60 jours |
| `abort-incomplete-multipart-uploads` | Supprime au bout de 7 jours les morceaux d'uploads interrompus, qui sinon sont facturés sans être visibles |

Vérification : S3 → `freetube-7k3q9x` → onglet **Gestion** : 3 règles, statut *Activé*.

---

## 2. Rôle IAM `freetube-ec2`

**Pourquoi :** le serveur a besoin d'accéder au bucket sans clé AWS écrite nulle part (D9). Un **rôle
d'instance** fournit des identifiants temporaires, renouvelés automatiquement, via le service de métadonnées
de l'instance (IMDSv2). Le hop limit à 2 que tu as déjà réglé permet aux conteneurs Docker d'y accéder.

### 2a. La politique

1. Console → **IAM** → **Politiques** → **Créer une politique**.
2. Onglet **JSON** : remplace tout par le contenu de `infra/iam/freetube-s3-policy.json`.
3. **Suivant**. Nom : `freetube-s3`. **Créer la politique**.

Elle n'autorise que lire, écrire et supprimer des objets de `freetube-7k3q9x`, et lister ce bucket. Rien d'autre.

### 2b. Le rôle

1. IAM → **Rôles** → **Créer un rôle**.
2. Type d'entité de confiance : *Service AWS*. Cas d'utilisation : **EC2**. **Suivant**.
3. La liste contient plus de 1 000 politiques, sur plusieurs pages : utilise la **barre de recherche**.
   Tape `freetube`, coche **`freetube-s3`**. Puis tape `AmazonSSMManagedInstanceCore` et coche-la aussi
   (les cases cochées restent cochées quand la recherche change). **Suivant**.
   Si `freetube-s3` reste introuvable : la politique (2a) n'a pas été créée, ou l'a été après l'ouverture de
   cet écran. Dans ce cas, clique sur le bouton d'actualisation ↻ à côté de la recherche.
   (`AmazonSSMManagedInstanceCore` permet d'ouvrir un terminal sur l'instance depuis la console, via
   Session Manager, même si SSH ne marche plus. C'est ta porte de secours.)
4. Nom : `freetube-ec2`. **Créer le rôle**.

### 2c. L'attacher à l'instance

1. Console → **EC2** → **Instances** → coche `freetube`.
2. **Actions** → **Sécurité** → **Modifier le rôle IAM**.
3. Choisis `freetube-ec2` → **Mettre à jour le rôle IAM**.
   Si un autre rôle était déjà attaché, il est remplacé, ce qui est voulu.

Vérification, depuis ton poste :

```bash
ssh freetube 'TOKEN=$(curl -s -X PUT http://169.254.169.254/latest/api/token -H "X-aws-ec2-metadata-token-ttl-seconds: 60"); curl -s -H "X-aws-ec2-metadata-token: $TOKEN" http://169.254.169.254/latest/meta-data/iam/security-credentials/'
```

La commande doit afficher `freetube-ec2`. Elle est en lecture seule.

---

## 3. Docker et Compose sur l'instance

**Pourquoi les paquets Ubuntu** (`docker.io`, `docker-compose-v2`, `docker-buildx`) plutôt que le dépôt de Docker Inc. :
ils reçoivent les correctifs de sécurité via `unattended-upgrades`, sans dépôt ni clé GPG à gérer.
En contrepartie, Docker y est un peu moins récent, ce qui ne gêne pas ce projet.

```bash
ssh freetube
sudo apt update
sudo apt install -y docker.io docker-compose-v2 docker-buildx git sqlite3
sudo usermod -aG docker ubuntu   # docker sans sudo ; ce groupe équivaut à root, acceptable sur une machine perso
exit
```

Reconnecte-toi pour que le groupe s'applique, puis vérifie :

```bash
ssh freetube 'docker version --format "{{.Server.Version}}" && docker compose version'
```

(`sqlite3` servira au script de sauvegarde, en M2.)

---

## 4. DNS chez OVH : ⚠️ coupe l'ancien site

**État actuel**, relevé le 2026-10-10 :

| Enregistrement | Valeur actuelle | TTL |
|---|---|---|
| `appartfinder.fr` A | `51.45.38.195` (ancien serveur) | 3600 s |
| `www.appartfinder.fr` A | `51.45.38.195` | — |
| AAAA (IPv6) | aucun | — |
| CAA | aucun | — |

Aucun AAAA ni CAA : rien ne gênera l'obtention du certificat par Caddy.

**Quand le faire :** une fois le serveur déployé (`docs/deploy.md`, étapes 1 à 3). Caddy réessaie
automatiquement d'obtenir le certificat tant que le DNS ne pointe pas sur lui ; l'ordre n'est donc pas
critique, mais ça évite des erreurs inutiles dans les logs.

**Dès que l'enregistrement est modifié, l'ancien site n'est plus joignable par son nom** (après au plus 1 h,
le temps que l'ancien TTL expire).

1. **manager.ovh.com** → **Web Cloud** → **Noms de domaine** → `appartfinder.fr` → onglet **Zone DNS**.
2. Ligne de type **A** avec un sous-domaine **vide** (`appartfinder.fr.`) → **…** → **Modifier**.
   Cible : `15.237.133.3`. TTL : laisse la valeur par défaut. **Valider**.
3. Ligne **A** du sous-domaine `www` : **supprime-la**.
   Pourquoi : rien ne servira `www`, et le Caddyfile ne le déclare pas. Le laisser pointer vers l'ancien
   serveur donnerait un site mort, ou pire, un vieux site encore vivant.
4. S'il existe une ligne **AAAA** pour le domaine, supprime-la (il n'y en avait pas lors du relevé).

Vérification, depuis ton poste. Le résultat attendu est `15.237.133.3` (au plus 1 h d'attente) :

```bash
curl -s "https://dns.google/resolve?name=appartfinder.fr&type=A" | grep -o '"data":"[^"]*"'
```

---

## 5. Snapshots EBS quotidiens (Data Lifecycle Manager)

**Pourquoi :** si le disque est perdu ou corrompu, on restaure l'instance entière. C'est complémentaire de la
sauvegarde SQLite sur S3 (M2), qui sert à restaurer seulement la base.

1. Console → **EC2** → **Elastic Block Store** → **Lifecycle Manager** → **Créer une politique de cycle de vie**.
2. Type : **Politique de snapshot EBS personnalisée**. **Suivant**.
3. Type de ressource cible : **Instance**. Balises de ressource cible : `Name` = `freetube`.
   (Vérifie que l'instance porte bien cette balise : EC2 → Instances → `freetube` → onglet **Balises**.)
4. Description : `freetube daily snapshots`. Rôle IAM : **Rôle par défaut** (créé automatiquement).
5. Statut : **Activé**. **Suivant**.
6. Planification 1 : fréquence **Quotidienne**, toutes les **24 heures**, début **03:00 UTC**.
   Type de conservation : **Nombre**, **7**.
7. Coche **Copier les balises de la source**. **Vérifier la politique** → **Créer la politique**.

Vérification, le lendemain : EC2 → **Snapshots** → un snapshot taggé `aws:dlm:lifecycle-policy-id`.

---

## 6. Mises à jour automatiques de sécurité

Lecture seule :

```bash
ssh freetube 'systemctl is-active unattended-upgrades; cat /etc/apt/apt.conf.d/20auto-upgrades'
```

Attendu : `active`, puis deux lignes contenant `"1"` (`Update-Package-Lists` et `Unattended-Upgrade`).

Si ce n'est pas le cas :

```bash
ssh freetube 'sudo dpkg-reconfigure -plow unattended-upgrades'   # répondre « Oui »
```

Optionnel, et recommandé : les mises à jour du noyau ne s'appliquent qu'après un redémarrage. Pour
redémarrer automatiquement à 04:00 quand c'est nécessaire, fais :

```bash
ssh freetube 'echo -e "Unattended-Upgrade::Automatic-Reboot \"true\";\nUnattended-Upgrade::Automatic-Reboot-Time \"04:00\";" | sudo tee /etc/apt/apt.conf.d/52freetube-reboot'
```

Les conteneurs redémarrent seuls (`restart: unless-stopped`). Coût : quelques secondes de coupure, la nuit.

---

## 7. Alerte de budget

1. Console → **Facturation et gestion des coûts** → **Budgets** → **Créer un budget**.
2. **Utiliser un modèle** → **Budget mensuel des coûts**.
3. Montant : un peu au-dessus de ta facture habituelle. Regarde le mois précédent dans **Factures** ;
   une instance t4g.small, 20 Gio de disque, une IP publique et quelques Gio de S3 coûtent de l'ordre de
   20 $/mois. Ce chiffre est indicatif, à vérifier sur ta facture.
4. Destinataire : ton email. **Créer un budget**.

Le modèle alerte à 85 % et 100 % du montant réel, et à 100 % du montant prévu.

---

## 8. Ménage : en M7 uniquement

À faire **seulement quand FreeTube est validé en prod**.

1. EC2 → Instances → l'ancienne instance appartfinder : vérifie qu'il n'y reste rien d'utile, puis
   désactive sa protection contre la résiliation si elle est activée, et **Résilier**.
   Supprime aussi son IP élastique si elle en avait une (une IP non attachée est facturée).
2. IAM → Rôles → `appartfinder-ec2` → **Supprimer**.
3. IAM → Politiques → la politique Bedrock associée, si c'est une politique gérée par le client → **Supprimer**.
