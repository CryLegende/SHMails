<div align="center">

<img src="assets/logo.png" alt="SkyHand" width="140">

# SHMails

*Boîte aux lettres en jeu (/mail) : envoi, réponse, notification à la connexion et outils staff.*

<img src="https://img.shields.io/badge/Paper-26.3-f2a900?style=for-the-badge" alt="Paper 26.3"> <img src="https://img.shields.io/badge/Java-25-e76f00?style=for-the-badge" alt="Java 25"> <img src="https://img.shields.io/badge/SkyHand-Opale-3aa655?style=for-the-badge" alt="SkyHand Opale">

</div>

---

## Présentation

Boîte aux lettres en jeu (/mail) : envoi, réponse, notification à la connexion et outils staff.

- **Réponse par dialogue natif** : sur un client 1.21.6 ou plus, un clic gauche sur un mail ouvre un dialogue qui rappelle le mail reçu (auteur, date, texte) avec un champ de réponse et deux boutons, **Envoyer** et **Annuler**, à la place de « tapez votre message dans le chat ». Échap annule proprement.
- **Mêmes règles que le chat** : la réponse part par la même méthode d'envoi (boîte du destinataire pleine, notification, espionnage staff), le mail d'origine est supprimé comme avant, 256 caractères au plus et ni `§` ni caractère de contrôle, comme le chat de Minecraft.
- **Saisie à usage unique** : chaque dialogue porte un jeton aléatoire ; un clic n'est pris en compte que si le jeton correspond au dialogue en cours du joueur, avant expiration (`dialogues.expiration-secondes`). La saisie est annulée si le joueur se déconnecte.
- **Repli sur le chat** : les clients plus anciens (ViaVersion donne leur version), `dialogues.actif: false` ou une API de dialogues absente gardent exactement le mode réponse par chat d'avant (« cancel » pour annuler).
- **Suppression sans mail fantôme** : après un clic droit « supprimer » (ou « tout supprimer »), le menu est reconstruit depuis le cache déjà à jour, au tick suivant, au lieu d'une relecture de la base qui pouvait devancer la suppression et réafficher le mail. L'item disparaît du menu au moment du clic.

Utilisé sur : **Opale**.

## Mode réseau

Avec **SHNetwork** installé (et `reseau.actif: true`, valeur par défaut), plusieurs serveurs de jeu partagent les mails :

- les boîtes sont en MariaDB (tables `shmails_mails`, `shmails_boxes`, `shmails_spies`) au lieu de `shmails.db` ou des fichiers YAML. Chaque serveur ne garde en mémoire que les boîtes des joueurs qui s'y trouvent (et celles qu'un admin consulte), jamais plus une copie pour tout le réseau ;
- à l'arrivée d'un joueur, une fois que l'autre serveur a confirmé sa sauvegarde, sa boîte est lue en base hors du fil principal, puis la notification de connexion est envoyée. La notification est donc renvoyée à chaque changement de serveur (`login_notification_delay_ticks: -1` la coupe) ;
- `/mail <joueur> <message>` cherche le destinataire sur tout le réseau (connecté ailleurs, déjà venu sur ce serveur, ou registre des premières connexions `Global.sh_track_firstseen` de SHPlayerTrack) au lieu du fichier de joueur de ce serveur ;
- un mail est déposé en base sous le verrou de la boîte du destinataire, qui compte `max_mails_per_player` : deux serveurs qui écrivent en même temps ne dépassent jamais la limite. Le bus (`reseau.canal`) prévient ensuite les autres serveurs : celui du destinataire le notifie une seule fois et met sa boîte à jour, tous préviennent leurs espions ;
- supprimer un mail ou une boîte (le joueur, ou `/shmailadmin clear`) écrit en base, puis prévient les autres serveurs qui relisent la boîte qu'ils ont en mémoire ;
- l'espionnage (`/shmailadmin spy`) est écrit en base et suit l'admin d'un serveur à l'autre, jusqu'à ce qu'il le coupe ; `spies.yml` n'est plus utilisé ;
- `/shmailadmin import` (permission `shmails.admin.import`, console comprise) reprend en base les mails (SQLite ou YAML selon `storage_type`) et les espions de `spies.yml` du serveur où on la lance. À faire une seule fois, sur le serveur qui a les mails ; un mail déjà en base (même identifiant) n'est pas dupliqué, la commande peut être relancée.

Sans SHNetwork, ou sans les clés `reseau` dans la configuration, le plugin se comporte comme avant : SQLite ou YAML local, `spies.yml`. Si la base est injoignable au démarrage, le plugin retombe sur le stockage local. Les tables se créent toutes seules au démarrage, ou à la main avec `sql/schema.sql`.

## Dépendances

- Optionnelles : `ViaVersion` (version du client pour choisir dialogue ou chat ; sans lui tous les clients sont considérés compatibles), `SHNetwork` (mode réseau)

## Commandes

| Commande | Alias | Description | Permission |
|---|---|---|---|
| `/mail` | `/mails`, `/shmails` | Commande principale pour les mails. | — |
| `/shmailadmin` | `/shma` | Commandes d'administration pour SHMails. | `shmails.admin` |

## Permissions

| Permission | Description | Par défaut |
|---|---|---|
| `shmails.use` | Permet d'utiliser les fonctionnalités de base des mails (ouvrir la boîte). | `true` |
| `shmails.send` | Permet d'envoyer des mails. | `true` |
| `shmails.admin` | Permet d'utiliser les commandes d'administration de base. | `op` |
| `shmails.admin.view` | Permet de voir les mails d'un autre joueur. | `op` |
| `shmails.admin.view.interact` | Permet d'interagir (répondre/supprimer) avec les mails lors de la visualisation admin. | `op` |
| `shmails.admin.spy` | Permet d'espionner les mails envoyés. | `op` |
| `shmails.admin.reload` | Permet de recharger la configuration du plugin. | `op` |
| `shmails.admin.clear` | Permet de supprimer les mails d'un joueur via commande admin. | `op` |
| `shmails.admin.import` | Mode réseau, permet de reprendre en base partagée les mails de ce serveur. | `op` |

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHMails/
└── <a href="src/main/resources/config.yml">config.yml</a>      dialogues.actif, dialogues.min-protocol (771 = 1.21.6), dialogues.expiration-secondes, dialogues.reponse (textes),
                    reseau (actif, attente-max-secondes, canal, table-premieres-connexions, base-de-donnees)
</pre>

## Compilation

Java 25 et Maven :

```bash
mvn clean package
```

Le jar est généré dans `target/`.

---

<div align="center">

<sub>Développé par <b>Cry_Legende</b> pour le serveur <b>SkyHand</b> · Tous droits réservés</sub>

</div>
