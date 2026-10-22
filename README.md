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

## Dépendances

- Optionnelles : `ViaVersion` (version du client pour choisir dialogue ou chat ; sans lui tous les clients sont considérés compatibles)

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

## Configuration

Fichiers par défaut repris du serveur (secrets remplacés par `CHANGE_ME`). Cliquez sur un fichier pour l'ouvrir :

<pre>
plugins/SHMails/
└── <a href="src/main/resources/config.yml">config.yml</a>      dialogues.actif, dialogues.min-protocol (771 = 1.21.6), dialogues.expiration-secondes, dialogues.reponse (textes)
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
