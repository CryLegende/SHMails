package org.Cry_Legende.sHMails;

import org.Cry_Legende.sHMails.commands.AdminMailCommand;
import org.Cry_Legende.sHMails.commands.MailCommand;
import org.Cry_Legende.sHMails.dialog.ReplyDialogListener;
import org.Cry_Legende.sHMails.dialog.ReplyDialogs;
import org.Cry_Legende.sHMails.listeners.InventoryClickListener;
import org.Cry_Legende.sHMails.listeners.PlayerChatListener;
import org.Cry_Legende.sHMails.listeners.PlayerJoinListener;
import org.Cry_Legende.sHMails.manager.ConfigManager;
import org.Cry_Legende.sHMails.manager.DatabaseManager;
import org.Cry_Legende.sHMails.manager.GUIManager;
import org.Cry_Legende.sHMails.manager.MailManager;
import org.Cry_Legende.sHMails.network.MailDatabase;
import org.Cry_Legende.sHMails.network.NetworkLink;
import org.Cry_Legende.sHMails.network.NetworkMails;
import org.Cry_Legende.sHMails.network.ShNetworkLink;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class SHMails extends JavaPlugin {

    private static SHMails instance;
    private ConfigManager configManager;
    private MailManager mailManager;
    private GUIManager guiManager;
    private DatabaseManager databaseManager;
    // null si l'API des dialogues est absente du serveur (réponse par chat uniquement)
    private ReplyDialogs replyDialogs;
    private final Set<UUID> mailSpies = new HashSet<>(); // Ceci devient le cache en mémoire
    // Non nul seulement avec SHNetwork : boîtes et espions sont dans la base partagée
    private NetworkMails network;

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
        boolean networkWanted = networkWanted();
        if (!networkWanted) {
            loadSpies(); // Charger les spies avant tout
        }

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
        if (networkWanted) {
            enableNetwork();
            if (network == null) {
                loadSpies(); // base partagée inutilisable : espions locaux comme sans SHNetwork
            }
        }

        registerCommands();
        registerListeners();

        getLogger().info("SHMails v" + getDescription().getVersion() + " enabled successfully!");
        getLogger().info("Using storage type: " + configManager.getStorageType());
    }

    @Override
    public void onDisable() {
        if (replyDialogs != null) {
            replyDialogs.shutdown();
        }
        if (network == null) {
            saveSpies(); // Sauvegarder les spies
        }
        if (mailManager != null) {
            mailManager.saveAllPlayerData();
        }
        if (network != null) {
            network.stop();
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
        registerDialogs();
    }

    /**
     * Réponse par dialogue natif (1.21.6+). Si l'API des dialogues n'existe pas sur ce serveur, le plugin garde le mode
     * réponse par chat : un dialogue n'est jamais indispensable.
     */
    private void registerDialogs() {
        try {
            ReplyDialogs dialogs = new ReplyDialogs(this);
            Bukkit.getPluginManager().registerEvents(new ReplyDialogListener(this, dialogs), this);
            replyDialogs = dialogs;
        } catch (RuntimeException | LinkageError e) {
            replyDialogs = null;
            getLogger().log(Level.WARNING, "API des dialogues indisponible : réponse par chat uniquement.", e);
        }
    }

    /** Mode réseau voulu : SHNetwork chargé et reseau.actif vrai (par défaut). */
    private boolean networkWanted() {
        return getConfig().getBoolean("reseau.actif", true) && Bukkit.getPluginManager().isPluginEnabled("SHNetwork");
    }

    /**
     * Passe en mode réseau : boîtes et espions dans la base partagée. Les classes qui touchent à SHNetwork ne sont
     * chargées qu'ici : sans lui, le plugin se comporte exactement comme avant.
     */
    private void enableNetwork() {
        MailDatabase db = null;
        try {
            NetworkLink link = ShNetworkLink.open();
            db = new MailDatabase(
                getConfig().getString("reseau.base-de-donnees.url"),
                getConfig().getString("reseau.base-de-donnees.utilisateur"),
                getConfig().getString("reseau.base-de-donnees.mot-de-passe"),
                Math.max(250, getConfig().getInt("reseau.base-de-donnees.delai-ms", 3000)));
            db.createTables();
            Duration wait = Duration.ofSeconds(Math.max(1, getConfig().getInt("reseau.attente-max-secondes", 10)));
            String channel = getConfig().getString("reseau.canal", "shmails").toLowerCase();
            String registry = getConfig().getString("reseau.table-premieres-connexions", "Global.sh_track_firstseen");
            if (!registry.matches("[A-Za-z0-9_.]*")) {
                getLogger().warning("reseau.table-premieres-connexions ignorée (nom de table invalide) : " + registry);
                registry = "";
            }
            NetworkMails net = new NetworkMails(this, db, link, wait, channel, registry);
            mailManager.setNetwork(net);
            net.start();
            network = net;
            getLogger().info("SHNetwork détecté : les mails et les espions sont partagés entre les serveurs (base partagée).");
        } catch (Exception | LinkageError e) {
            mailManager.setNetwork(null);
            if (db != null) {
                db.close();
            }
            getLogger().log(Level.SEVERE, "SHNetwork présent mais inutilisable, mails locaux à ce serveur : " + e);
        }
    }

    /** Mode réseau actif, ou null sur un serveur seul. */
    public NetworkMails getNetwork() {
        return network;
    }

    public void loadSpies() {
        if (network != null) {
            return; // les espions viennent de la base partagée, relus à l'arrivée de chaque admin
        }
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

    /** Espions de spies.yml, pour la reprise en base partagée (mode réseau). */
    public List<UUID> readLocalSpies() {
        List<UUID> spies = new java.util.ArrayList<>();
        File file = new File(getDataFolder(), "spies.yml");
        if (!file.isFile()) {
            return spies;
        }
        for (String uuidStr : YamlConfiguration.loadConfiguration(file).getStringList("spying-admins")) {
            try {
                spies.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Invalid UUID found in spies.yml: " + uuidStr);
            }
        }
        return spies;
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

    /**
     * Réponse par dialogue, ou null si l'API des dialogues est indisponible.
     */
    public ReplyDialogs getReplyDialogs() {
        return replyDialogs;
    }

    public Set<UUID> getMailSpies() { // Ceci est maintenant le cache en mémoire
        return mailSpies;
    }

    public void addSpy(UUID uuid) {
        mailSpies.add(uuid);
        if (network != null) {
            network.setSpy(uuid, true);
        } else {
            saveSpies(); // Sauvegarder immédiatement
        }
    }

    public void removeSpy(UUID uuid) {
        mailSpies.remove(uuid);
        if (network != null) {
            network.setSpy(uuid, false);
        } else {
            saveSpies(); // Sauvegarder immédiatement
        }
    }

    public boolean isSpying(UUID uuid) {
        return mailSpies.contains(uuid);
    }
}
