package org.Cry_Legende.sHMails.network;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.Cry_Legende.sHMails.model.Mail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Mails et espions en MariaDB, partagés par tous les serveurs. Chargée seulement avec SHNetwork
 * (HikariCP et le pilote MariaDB viennent de {@code libraries:} dans plugin.yml).
 *
 * <p>Toutes les méthodes bloquent : à appeler hors du fil principal.</p>
 */
public final class MailDatabase {

    private static final String MAILS = "shmails_mails";
    private static final String BOXES = "shmails_boxes";
    private static final String SPIES = "shmails_spies";

    /** Issue d'un dépôt : stored faux si la boîte du destinataire est pleine (count = nombre de mails qu'elle contient). */
    public record Delivery(boolean stored, int count) {
    }

    private final HikariDataSource pool;

    public MailDatabase(String url, String user, String password, int timeoutMs) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("SHMails");
        hikari.setJdbcUrl(url);
        hikari.setUsername(user);
        hikari.setPassword(password);
        hikari.setDriverClassName("org.mariadb.jdbc.Driver");
        hikari.addDataSourceProperty("connectTimeout", String.valueOf(timeoutMs));
        hikari.addDataSourceProperty("socketTimeout", String.valueOf(timeoutMs));
        hikari.setMaximumPoolSize(2);
        hikari.setMinimumIdle(1);
        hikari.setConnectionTimeout(timeoutMs);
        // le serveur démarre même si la base est absente : les opérations échouent alors une à une, avec un message
        hikari.setInitializationFailTimeout(-1);
        hikari.setKeepaliveTime(60_000);
        this.pool = new HikariDataSource(hikari);
    }

    /** Crée les tables si elles manquent. Des tables déjà là ne demandent aucun droit CREATE à l'utilisateur. */
    public void createTables() throws SQLException {
        try (Connection c = pool.getConnection(); Statement st = c.createStatement()) {
            if (!exists(st, MAILS)) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + MAILS + " ("
                    + "mail_id CHAR(36) NOT NULL PRIMARY KEY, "
                    + "recipient_uuid CHAR(36) NOT NULL, "
                    + "sender_uuid CHAR(36) NOT NULL, "
                    + "sender_name VARCHAR(32) NOT NULL, "
                    + "message TEXT NOT NULL, "
                    + "sent_at BIGINT NOT NULL, "
                    + "KEY idx_recipient (recipient_uuid, sent_at)"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            }
            if (!exists(st, BOXES)) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + BOXES + " ("
                    + "uuid CHAR(36) NOT NULL PRIMARY KEY"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            }
            if (!exists(st, SPIES)) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS " + SPIES + " ("
                    + "uuid CHAR(36) NOT NULL PRIMARY KEY"
                    + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci");
            }
        }
    }

    private static boolean exists(Statement st, String table) {
        try (ResultSet rs = st.executeQuery("SELECT 1 FROM " + table + " LIMIT 1")) {
            return true;
        } catch (SQLException missing) {
            return false;
        }
    }

    /** Boîte du joueur, du plus récent au plus ancien. */
    public List<Mail> loadBox(UUID recipient) throws SQLException {
        List<Mail> mails = new ArrayList<>();
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT mail_id, sender_uuid, sender_name, message, sent_at FROM " + MAILS
                 + " WHERE recipient_uuid = ? ORDER BY sent_at DESC, mail_id")) {
            ps.setString(1, recipient.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    mails.add(new Mail(UUID.fromString(rs.getString(1)), UUID.fromString(rs.getString(2)), rs.getString(3),
                        recipient, rs.getString(4), rs.getLong(5)));
                }
            }
        }
        return mails;
    }

    /**
     * Dépose le mail dans la boîte de son destinataire, sauf si elle contient déjà {@code max} mails (0 = sans limite).
     * Le compte et l'insertion se font sous le verrou de la ligne du destinataire dans {@code shmails_boxes} : deux
     * serveurs qui écrivent en même temps au même destinataire ne dépassent jamais la limite.
     */
    public Delivery deliver(Mail mail, int max) throws SQLException {
        String recipient = mail.getRecipientUUID().toString();
        try (Connection c = pool.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + BOXES + " (uuid) VALUES (?)")) {
                    ps.setString(1, recipient);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement("SELECT uuid FROM " + BOXES + " WHERE uuid = ? FOR UPDATE")) {
                    ps.setString(1, recipient);
                    ps.executeQuery().close();
                }
                int count = 0;
                if (max > 0) {
                    try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + MAILS + " WHERE recipient_uuid = ?")) {
                        ps.setString(1, recipient);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                count = rs.getInt(1);
                            }
                        }
                    }
                    if (count >= max) {
                        c.rollback();
                        return new Delivery(false, count);
                    }
                }
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO " + MAILS
                    + " (mail_id, recipient_uuid, sender_uuid, sender_name, message, sent_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, mail.getMailId().toString());
                    ps.setString(2, recipient);
                    ps.setString(3, mail.getSenderUUID().toString());
                    ps.setString(4, mail.getSenderName());
                    ps.setString(5, mail.getMessage());
                    ps.setLong(6, mail.getTimestamp());
                    ps.executeUpdate();
                }
                c.commit();
                return new Delivery(true, count + 1);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        }
    }

    public void deleteMail(UUID mailId) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM " + MAILS + " WHERE mail_id = ?")) {
            ps.setString(1, mailId.toString());
            ps.executeUpdate();
        }
    }

    public void deleteBox(UUID recipient) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM " + MAILS + " WHERE recipient_uuid = ?")) {
            ps.setString(1, recipient.toString());
            ps.executeUpdate();
        }
    }

    public boolean isSpy(UUID uuid) throws SQLException {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT 1 FROM " + SPIES + " WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public void setSpy(UUID uuid, boolean spying) throws SQLException {
        String sql = spying ? "INSERT IGNORE INTO " + SPIES + " (uuid) VALUES (?)" : "DELETE FROM " + SPIES + " WHERE uuid = ?";
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.executeUpdate();
        }
    }

    /**
     * Pseudo exact d'un joueur déjà venu sur le réseau d'après le registre des premières connexions (colonnes name, en
     * minuscules, et display_name), ou null. Un registre absent ou illisible rend null.
     */
    public String firstSeen(String table, String name) {
        try (Connection c = pool.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT display_name FROM " + table + " WHERE name = ? LIMIT 1")) {
            ps.setString(1, name.toLowerCase(java.util.Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            return null;
        }
    }

    /**
     * Reprise de mails et d'espions locaux. Un mail déjà en base (même identifiant) est laissé tel quel.
     *
     * @return nombre de mails ajoutés
     */
    public int importAll(Collection<Mail> mails, Collection<UUID> spies) throws SQLException {
        try (Connection c = pool.getConnection()) {
            int before = countMails(c);
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + MAILS
                + " (mail_id, recipient_uuid, sender_uuid, sender_name, message, sent_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                int batch = 0;
                for (Mail mail : mails) {
                    ps.setString(1, mail.getMailId().toString());
                    ps.setString(2, mail.getRecipientUUID().toString());
                    ps.setString(3, mail.getSenderUUID().toString());
                    ps.setString(4, mail.getSenderName() == null ? "?" : mail.getSenderName());
                    ps.setString(5, mail.getMessage() == null ? "" : mail.getMessage());
                    ps.setLong(6, mail.getTimestamp());
                    ps.addBatch();
                    if (++batch % 500 == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO " + SPIES + " (uuid) VALUES (?)")) {
                for (UUID spy : spies) {
                    ps.setString(1, spy.toString());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return countMails(c) - before;
        }
    }

    private static int countMails(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + MAILS)) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    public void close() {
        pool.close();
    }
}
