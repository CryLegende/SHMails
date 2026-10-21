package org.Cry_Legende.sHMails;

import org.Cry_Legende.sHMails.commands.AdminMailCommand;
import org.Cry_Legende.sHMails.commands.MailCommand;
import org.Cry_Legende.sHMails.listeners.InventoryClickListener;
import org.Cry_Legende.sHMails.listeners.PlayerChatListener;
import org.Cry_Legende.sHMails.listeners.PlayerJoinListener;
import org.Cry_Legende.sHMails.manager.ConfigManager;
import org.Cry_Legende.sHMails.manager.DatabaseManager;
import org.Cry_Legende.sHMails.manager.GUIManager;
import org.Cry_Legende.sHMails.manager.MailManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SHMails extends JavaPlugin {

    private static SHMails instance;
    private ConfigManager configManager;
    private MailManager mailManager;
    private GUIManager guiManager;
    private DatabaseManager databaseManager;
    private final Set<UUID> mailSpies = new HashSet<>(); // Ceci devient le cache en mémoire

    public final Map<UUID, UUID> replyingToOriginalMailId = new ConcurrentHashMap<>();
    public final Map<UUID, UUID> replyingToSenderUUID = new ConcurrentHashMap<>();
    public final Map<UUID, String> replyingToSenderName = new ConcurrentHashMap<>();

    private File spiesFile;
    private FileConfiguration spiesConfig;


    @Override
    public void onEnable() {
        instance = this;

        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        File dataFolderForYaml = new File(getDataFolder(), "data");
        if (!dataFolderForYaml.exists()) {
            dataFolderForYaml.mkdirs();
        }

        this.configManager = new ConfigManager(this);
        loadSpies(); // Charger les spies avant tout

        if (configManager.getStorageType().equalsIgnoreCase("sqlite")) {
            this.databaseManager = new DatabaseManager(this);
            try {
                this.databaseManager.initializeDatabase();
            } catch (SQLException e) {
                getLogger().severe("Failed to initialize SQLite database! Disabling plugin or features might occur.");
                getLogger().severe(e.getMessage());
                e.printStackTrace();
                this.databaseManager = null;
            }
        } else {
            getLogger().info("Using YAML storage for mails.");
        }

        this.mailManager = new MailManager(this);
        this.guiManager = new GUIManager(this);

        registerCommands();
        registerListeners();

        getLogger().info("SHMails v" + getDescription().getVersion() + " enabled successfully!");
        getLogger().info("Using storage type: " + configManager.getStorageType());
    }

    @Override
    public void onDisable() {
        saveSpies(); // Sauvegarder les spies
        if (mailManager != null) {
            mailManager.saveAllPlayerData();
        }

        // Le bloc pour fermer la connexion a été supprimé car ce n'est plus nécessaire.
        // Chaque opération de base de données gère sa propre connexion.

        replyingToOriginalMailId.clear();
        replyingToSenderUUID.clear();
        replyingToSenderName.clear();
        getLogger().info("SHMails disabled.");
    }

    private void registerCommands() {
        MailCommand mailCommandExecutor = new MailCommand(this);
        getCommand("mail").setExecutor(mailCommandExecutor);
        getCommand("mail").setTabCompleter(mailCommandExecutor);

        AdminMailCommand adminMailCommandExecutor = new AdminMailCommand(this);
        getCommand("shmailadmin").setExecutor(adminMailCommandExecutor);
        getCommand("shmailadmin").setTabCompleter(adminMailCommandExecutor);
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        Bukkit.getPluginManager().registerEvents(new InventoryClickListener(this), this);
        Bukkit.getPluginManager().registerEvents(new PlayerChatListener(this), this);
    }

    public void loadSpies() {
        spiesFile = new File(getDataFolder(), "spies.yml");
        if (!spiesFile.exists()) {
            try {
                spiesFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Could not create spies.yml: " + e.getMessage());
                return;
            }
        }
        spiesConfig = YamlConfiguration.loadConfiguration(spiesFile);
        List<String> spyUuidsAsStrings = spiesConfig.getStringList("spying-admins");
        mailSpies.clear(); // Nettoyer le cache avant de charger
        for (String uuidStr : spyUuidsAsStrings) {
            try {
                mailSpies.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid UUID found in spies.yml: " + uuidStr);
            }
        }
        getLogger().info("Loaded " + mailSpies.size() + " admin spies.");
    }

    public void saveSpies() {
        if (spiesConfig == null || spiesFile == null) {
            return;
        }
        List<String> spyUuidsAsStrings = mailSpies.stream()
            .map(UUID::toString)
            .collect(Collectors.toList());
        spiesConfig.set("spying-admins", spyUuidsAsStrings);
        try {
            spiesConfig.save(spiesFile);
            getLogger().info("Saved " + mailSpies.size() + " admin spies.");
        } catch (IOException e) {
            getLogger().severe("Could not save spies.yml: " + e.getMessage());
        }
    }

    public static SHMails getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MailManager getMailManager() {
        return mailManager;
    }

    public GUIManager getGuiManager() {
        return guiManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public Set<UUID> getMailSpies() { // Ceci est maintenant le cache en mémoire
        return mailSpies;
    }

    public void addSpy(UUID uuid) {
        mailSpies.add(uuid);
        saveSpies(); // Sauvegarder immédiatement
    }

    public void removeSpy(UUID uuid) {
        mailSpies.remove(uuid);
        saveSpies(); // Sauvegarder immédiatement
    }

    public boolean isSpying(UUID uuid) {
        return mailSpies.contains(uuid);
    }
}
