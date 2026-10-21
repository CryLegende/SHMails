package org.Cry_Legende.sHMails.manager;

import org.Cry_Legende.sHMails.SHMails;
import org.Cry_Legende.sHMails.model.Mail;
import org.Cry_Legende.sHMails.utils.ChatUtils;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class MailManager {

    private final SHMails plugin;
    private final ConfigManager configManager;
    private final DatabaseManager databaseManager;
    private final Map<UUID, List<Mail>> mailCache = new ConcurrentHashMap<>();
    private final SimpleDateFormat dateFormat;

    public MailManager(SHMails plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.databaseManager = plugin.getDatabaseManager();
        this.dateFormat = new SimpleDateFormat(configManager.getDateFormat());
    }

    public List<Mail> getPlayerMails(UUID playerUUID) {
        return mailCache.computeIfAbsent(playerUUID, this::loadPlayerDataFromSource);
    }

    public void sendMail(UUID senderUUID, String senderName, UUID recipientUUID, String messageContent) {
        new BukkitRunnable() {
            @Override
            public void run() {
                List<Mail> recipientMailsLoaded = getPlayerMails(recipientUUID);

                int maxMails = configManager.getMaxMailsPerPlayer();
                if (maxMails > 0 && recipientMailsLoaded.size() >= maxMails) {
                    Player senderOnline = Bukkit.getPlayer(senderUUID);
                    if (senderOnline != null && senderOnline.isOnline()) {
                        OfflinePlayer recipientOffline = Bukkit.getOfflinePlayer(recipientUUID);
                        new BukkitRunnable() {
                            @Override
                            public void run() {
                                senderOnline.sendMessage(configManager.getMessage("recipient_mailbox_full",
                                    "%recipient%", recipientOffline.getName() != null ? recipientOffline.getName() : "Joueur Inconnu",
                                    "%current%", String.valueOf(recipientMailsLoaded.size()),
                                    "%max%", String.valueOf(maxMails)
                                ));
                                configManager.playSound(senderOnline, "action_error");
                            }
                        }.runTask(plugin);
                    }
                    return;
                }

                Mail mail = new Mail(senderUUID, senderName, recipientUUID, messageContent);

                // Mettre à jour le cache
                List<Mail> cachedMails = mailCache.computeIfAbsent(recipientUUID, k -> new ArrayList<>());
                cachedMails.add(0, mail);
                cachedMails.sort(Comparator.comparingLong(Mail::getTimestamp).reversed());
                if (maxMails > 0 && cachedMails.size() > maxMails) { // S'assurer de ne pas dépasser la limite dans le cache aussi
                    cachedMails.remove(cachedMails.size() - 1);
                }


                if ("sqlite".equalsIgnoreCase(configManager.getStorageType()) && databaseManager != null) {
                    databaseManager.saveMailAsync(mail);
                } else {
                    savePlayerDataToFile(recipientUUID, cachedMails);
                }

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        Player recipientOnline = Bukkit.getPlayer(recipientUUID);
                        if (recipientOnline != null && recipientOnline.isOnline()) {
                            recipientOnline.sendMessage(configManager.getMessage("mail_received_notification", "%sender%", senderName));
                            configManager.playSound(recipientOnline, "mail_received");
                        }

                        Player senderOnline = Bukkit.getPlayer(senderUUID);
                        if (senderOnline != null && senderOnline.isOnline()) {
                            OfflinePlayer recipientOffline = Bukkit.getOfflinePlayer(recipientUUID);
                            senderOnline.sendMessage(configManager.getMessage("mail_sent", "%recipient%", recipientOffline.getName() != null ? recipientOffline.getName() : "Joueur Inconnu"));
                            configManager.playSound(senderOnline, "mail_sent");
                        }

                        String spyMessage = configManager.getRawMessage("admin_spy_format",
                            "%sender%", senderName,
                            "%recipient%", Bukkit.getOfflinePlayer(recipientUUID).getName() != null ? Bukkit.getOfflinePlayer(recipientUUID).getName() : "Inconnu",
                            "%message%", messageContent);

                        plugin.getMailSpies().forEach(spyUUID -> {
                            Player spy = Bukkit.getPlayer(spyUUID);
                            if (spy != null && spy.isOnline()) {
                                spy.sendMessage(spyMessage);
                            }
                        });
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }


    public void deleteMail(UUID playerUUID, UUID mailId) {
        List<Mail> mails = getPlayerMails(playerUUID); // Récupère du cache
        boolean removed = mails.removeIf(mail -> mail.getMailId().equals(mailId));
        if (removed) {
            if ("sqlite".equalsIgnoreCase(configManager.getStorageType()) && databaseManager != null) {
                databaseManager.deleteMailAsync(mailId);
            } else {
                savePlayerDataToFile(playerUUID, mails);
            }
        }
    }

    // Renommé de deleteAllReadMails
    public void deleteAllMailsForPlayer(UUID playerUUID) {
        List<Mail> mails = getPlayerMails(playerUUID);
        if (!mails.isEmpty()) {
            mails.clear(); // Vider le cache
            if ("sqlite".equalsIgnoreCase(configManager.getStorageType()) && databaseManager != null) {
                databaseManager.deleteAllMailsForPlayerAsync(playerUUID);
            } else {
                savePlayerDataToFile(playerUUID, mails); // Sauvegarde un fichier YAML vide
            }
        }
    }

    public Mail getMailById(UUID playerUUID, UUID mailId) {
        return getPlayerMails(playerUUID).stream()
            .filter(mail -> mail.getMailId().equals(mailId))
            .findFirst().orElse(null);
    }

    // markMailAsRead est supprimé

    public int getMailCount(UUID playerUUID) { // Renommé de getUnreadMailCount
        return getPlayerMails(playerUUID).size();
    }

    public String formatDate(long timestamp) {
        try {
            return dateFormat.format(new Date(timestamp));
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to format date: " + timestamp + " with format " + configManager.getDateFormat());
            return "Date Error";
        }
    }

    public List<String> formatMessageForLore(String message) {
        List<String> loreLines = new ArrayList<>();
        int maxLength = configManager.getMaxMessageLineLength();
        if (message == null || message.isEmpty()) {
            loreLines.add(ChatUtils.color("&7(Message vide)"));
            return loreLines;
        }

        StringBuilder currentLine = new StringBuilder();
        // Regex pour spliter par espace mais ne pas spliter à l'intérieur des guillemets (si jamais utilisé)
        String[] words = message.split(" (?=([^\"]*\"[^\"]*\")*[^\"]*$)");

        for (String word : words) {
            if (currentLine.length() + word.length() + (currentLine.length() > 0 ? 1 : 0) > maxLength) {
                if (currentLine.length() > 0) loreLines.add(currentLine.toString());
                currentLine = new StringBuilder(word);
                while (currentLine.length() > maxLength) {
                    loreLines.add(currentLine.substring(0, maxLength));
                    currentLine = new StringBuilder(currentLine.substring(maxLength));
                }
            } else {
                if (currentLine.length() > 0) {
                    currentLine.append(" ");
                }
                currentLine.append(word);
            }
        }
        if (currentLine.length() > 0) {
            loreLines.add(currentLine.toString());
        }
        return loreLines;
    }

    private List<Mail> loadPlayerDataFromSource(UUID playerUUID) {
        if ("sqlite".equalsIgnoreCase(configManager.getStorageType()) && databaseManager != null) {
            return databaseManager.loadMailsForPlayer(playerUUID);
        } else {
            return loadPlayerDataFromFile(playerUUID);
        }
    }

    public void loadPlayerDataAsync(UUID playerUUID, Runnable onLoadedCallback) {
        new BukkitRunnable() {
            @Override
            public void run() {
                List<Mail> mails = loadPlayerDataFromSource(playerUUID);
                mailCache.put(playerUUID, mails);
                if (onLoadedCallback != null) {
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            onLoadedCallback.run();
                        }
                    }.runTask(plugin);
                }
            }
        }.runTaskAsynchronously(plugin);
    }

    public void savePlayerData(UUID playerUUID) {
        if ("yaml".equalsIgnoreCase(configManager.getStorageType())) {
            List<Mail> mails = mailCache.get(playerUUID);
            if (mails != null) {
                savePlayerDataToFile(playerUUID, mails);
            }
        }
    }

    public void saveAllPlayerData() {
        if ("yaml".equalsIgnoreCase(configManager.getStorageType())) {
            mailCache.forEach(this::savePlayerDataToFile);
            plugin.getLogger().info("All YAML player mail data from cache saved.");
        }
    }

    public void unloadPlayerData(UUID playerUUID) {
        if ("yaml".equalsIgnoreCase(configManager.getStorageType())) {
            savePlayerData(playerUUID);
        }
        mailCache.remove(playerUUID);
    }

    private File getPlayerDataFile(UUID playerUUID) {
        return new File(plugin.getDataFolder() + File.separator + "data", playerUUID.toString() + ".yml");
    }

    private List<Mail> loadPlayerDataFromFile(UUID playerUUID) {
        File playerFile = getPlayerDataFile(playerUUID);
        if (!playerFile.exists()) {
            return new ArrayList<>();
        }
        FileConfiguration playerData = YamlConfiguration.loadConfiguration(playerFile);
        List<Mail> mails = new ArrayList<>();
        ConfigurationSection mailsSection = playerData.getConfigurationSection("mails");
        if (mailsSection != null) {
            for (String key : mailsSection.getKeys(false)) {
                ConfigurationSection mailSection = mailsSection.getConfigurationSection(key);
                if (mailSection != null) {
                    try {
                        UUID mailId = UUID.fromString(key);
                        UUID senderUUID = UUID.fromString(mailSection.getString("senderUUID"));
                        String senderName = mailSection.getString("senderName");
                        String message = mailSection.getString("message");
                        long timestamp = mailSection.getLong("timestamp");
                        // boolean read = mailSection.getBoolean("read", false); // Supprimé
                        mails.add(new Mail(mailId, senderUUID, senderName, playerUUID, message, timestamp));
                    } catch (Exception e) { // Catch plus large pour robustesse
                        plugin.getLogger().warning("Failed to load mail entry (ID: " + key + ") for " + playerUUID + " from YAML: " + e.getMessage());
                    }
                }
            }
        }
        mails.sort(Comparator.comparingLong(Mail::getTimestamp).reversed());
        return mails;
    }

    private void savePlayerDataToFile(UUID playerUUID, List<Mail> mails) {
        if (mails == null) return;

        File playerFile = getPlayerDataFile(playerUUID);
        FileConfiguration playerData = new YamlConfiguration();

        if (!mails.isEmpty()) {
            for (Mail mail : mails) {
                String mailIdStr = mail.getMailId().toString();
                playerData.set("mails." + mailIdStr + ".senderUUID", mail.getSenderUUID().toString());
                playerData.set("mails." + mailIdStr + ".senderName", mail.getSenderName());
                playerData.set("mails." + mailIdStr + ".message", mail.getMessage());
                playerData.set("mails." + mailIdStr + ".timestamp", mail.getTimestamp());
                // playerData.set("mails." + mailIdStr + ".read", mail.isRead()); // Supprimé
            }
        } else {
            playerData.set("mails", null);
        }

        try {
            playerData.save(playerFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save YAML mail data for " + playerUUID + ": " + e.getMessage());
        }
    }
}
