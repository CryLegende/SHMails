-- Tables de SHMails avec SHNetwork. Le plugin les crée seul au démarrage si l'utilisateur a le droit CREATE ;
-- ce fichier sert à les créer à la main et à ne donner à l'utilisateur que les droits de lecture et d'écriture.
--
-- Utilisation (base bo_net pour les essais multi-Opale) :
--   mysql -u root < sql/schema.sql   puis remplacer CHANGE_ME par le mot de passe de config.yml

CREATE DATABASE IF NOT EXISTS bo_net;

CREATE TABLE IF NOT EXISTS bo_net.shmails_mails (
    mail_id        CHAR(36)    NOT NULL PRIMARY KEY,   -- identifiant du mail
    recipient_uuid CHAR(36)    NOT NULL,               -- uuid « hors ligne » du destinataire, le même sur tous les serveurs
    sender_uuid    CHAR(36)    NOT NULL,
    sender_name    VARCHAR(32) NOT NULL,
    message        TEXT        NOT NULL,
    sent_at        BIGINT      NOT NULL,               -- envoi, en millisecondes depuis 1970
    KEY idx_recipient (recipient_uuid, sent_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Une ligne par destinataire déjà vu : son verrou, pris pendant que le plugin compte la boîte et y dépose un mail,
-- pour que deux serveurs qui écrivent en même temps ne dépassent jamais max_mails_per_player.
CREATE TABLE IF NOT EXISTS bo_net.shmails_boxes (
    uuid CHAR(36) NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

-- Admins qui espionnent les mails (/shmailadmin spy) : l'espionnage les suit d'un serveur à l'autre.
CREATE TABLE IF NOT EXISTS bo_net.shmails_spies (
    uuid CHAR(36) NOT NULL PRIMARY KEY
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE USER IF NOT EXISTS 'shmails'@'127.0.0.1' IDENTIFIED BY 'CHANGE_ME';
GRANT SELECT, INSERT, UPDATE, DELETE ON bo_net.shmails_mails TO 'shmails'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON bo_net.shmails_boxes TO 'shmails'@'127.0.0.1';
GRANT SELECT, INSERT, UPDATE, DELETE ON bo_net.shmails_spies TO 'shmails'@'127.0.0.1';
-- Registre des premières connexions (SHPlayerTrack), pour retrouver un destinataire jamais venu sur ce serveur
GRANT SELECT ON Global.sh_track_firstseen TO 'shmails'@'127.0.0.1';
