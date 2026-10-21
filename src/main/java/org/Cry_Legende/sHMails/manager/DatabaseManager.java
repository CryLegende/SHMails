package org.Cry_Legende.sHMails.manager;

import org.Cry_Legende.sHMails.SHMails;
import org.Cry_Legende.sHMails.model.Mail;
import org.bukkit.Bukkit;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class DatabaseManager {

    private final SHMails plugin;
    private final String dbPath;
    private final String TABLE_NAME = "player_mails_v2";

    public DatabaseManager(SHMails plugin) {
        this.plugin = plugin;
        // Définir le chemin une seule fois pour éviter les répétitions
        File dataFolder = new File(plugin.getDataFolder(), "shmails.db");
        this.dbPath = "jdbc:sqlite:" + dataFolder.getAbsolutePath();
    }

    // MODIFICATION 1 : Renommée pour plus de clarté. Crée TOUJOURS une nouvelle connexion.
    private Connection createConnection() throws SQLException {
        return DriverManager.getConnection(this.dbPath);
    }

    public void initializeDatabase() throws SQLException {
        // S'assurer que le dossier parent existe
        File dbFile = new File(plugin.getDataFolder(), "shmails.db");
        if (!dbFile.getParentFile().exists()) {
            dbFile.getParentFile().mkdirs();
        }

        String createTableSQL = "CREATE TABLE IF NOT EXISTS " + TABLE_NAME + " (" +
            "mail_id TEXT PRIMARY KEY NOT NULL, " +
            "recipient_uuid TEXT NOT NULL, " +
            "sender_uuid TEXT NOT NULL, " +
            "sender_name TEXT NOT NULL, " +
            "message TEXT NOT NULL, " +
            "timestamp INTEGER NOT NULL " +
            ");";
        // Utilise try-with-resources pour fermer la connexion/statement après initialisation
        try (Connection conn = createConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(createTableSQL);
        }
        plugin.getLogger().info("SQLite database table '" + TABLE_NAME + "' initialized successfully.");
    }

    // MODIFICATION 2 : La méthode closeConnection() est maintenant inutile.
    // Il n'y a plus de connexion globale à fermer.
    // public void closeConnection() { ... } // Supprimez cette méthode.

    public void saveMailAsync(Mail mail) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "INSERT INTO " + TABLE_NAME + "(mail_id, recipient_uuid, sender_uuid, sender_name, message, timestamp) " +
                "VALUES(?,?,?,?,?,?)";
            // MODIFICATION 3 : Le try-with-resources utilise maintenant createConnection()
            try (Connection conn = createConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, mail.getMailId().toString());
                pstmt.setString(2, mail.getRecipientUUID().toString());
                pstmt.setString(3, mail.getSenderUUID().toString());
                pstmt.setString(4, mail.getSenderName());
                pstmt.setString(5, mail.getMessage());
                pstmt.setLong(6, mail.getTimestamp());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Error saving mail to SQLite: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    public List<Mail> loadMailsForPlayer(UUID recipientUUID) {
        List<Mail> mails = new ArrayList<>();
        String sql = "SELECT * FROM " + TABLE_NAME + " WHERE recipient_uuid = ? ORDER BY timestamp DESC";
        // MODIFICATION 3 : Le try-with-resources utilise maintenant createConnection()
        try (Connection conn = createConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, recipientUUID.toString());
            ResultSet rs = pstmt.executeQuery();
            while (rs.next()) {
                mails.add(new Mail(
                    UUID.fromString(rs.getString("mail_id")),
                    UUID.fromString(rs.getString("sender_uuid")),
                    rs.getString("sender_name"),
                    recipientUUID,
                    rs.getString("message"),
                    rs.getLong("timestamp")
                ));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error loading mails for " + recipientUUID + " from SQLite: " + e.getMessage());
            e.printStackTrace();
        }
        return mails;
    }

    public void deleteMailAsync(UUID mailId) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "DELETE FROM " + TABLE_NAME + " WHERE mail_id = ?";
            // MODIFICATION 3 : Le try-with-resources utilise maintenant createConnection()
            try (Connection conn = createConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, mailId.toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Error deleting mail " + mailId + " from SQLite: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    public void deleteAllMailsForPlayerAsync(UUID recipientUUID) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "DELETE FROM " + TABLE_NAME + " WHERE recipient_uuid = ?";
            // MODIFICATION 3 : Le try-with-resources utilise maintenant createConnection()
            try (Connection conn = createConnection(); PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, recipientUUID.toString());
                pstmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Error deleting all mails for " + recipientUUID + " from SQLite: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
}
