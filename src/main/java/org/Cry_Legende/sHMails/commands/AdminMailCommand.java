package org.Cry_Legende.sHMails.commands;

import org.Cry_Legende.sHMails.SHMails;
import org.Cry_Legende.sHMails.manager.ConfigManager;
import org.Cry_Legende.sHMails.manager.GUIManager;
import org.Cry_Legende.sHMails.manager.MailManager;
import org.Cry_Legende.sHMails.network.NetworkMails;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class AdminMailCommand implements CommandExecutor, TabCompleter {

    private final SHMails plugin;
    private final ConfigManager configManager;
    private final GUIManager guiManager;
    private final MailManager mailManager;


    public AdminMailCommand(SHMails plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.guiManager = plugin.getGuiManager();
        this.mailManager = plugin.getMailManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("shmails.admin")) {
            sender.sendMessage(configManager.getMessage("no_permission"));
            if (sender instanceof Player) configManager.playSound((Player) sender, "action_error");
            return true;
        }

        if (args.length == 0) {
            sendAdminHelp(sender, label);
            return true;
        }

        String subCommand = args[0].toLowerCase();
        Player playerSender = (sender instanceof Player) ? (Player) sender : null;


        switch (subCommand) {
            case "view":
                // ... (inchangé) ...
                if (!sender.hasPermission("shmails.admin.view")) {
                    sender.sendMessage(configManager.getMessage("no_permission"));
                    if (playerSender != null) configManager.playSound(playerSender, "action_error");
                    return true;
                }
                if (playerSender == null) {
                    sender.sendMessage(configManager.getMessage("must_be_player"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(configManager.getPrefix() + "&cUsage: /" + label + " view <joueur>");
                    if (playerSender != null) configManager.playSound(playerSender, "action_error");
                    return true;
                }

                if (plugin.getNetwork() != null) {
                    plugin.getNetwork().resolve(args[1], known -> {
                        if (known == null) {
                            sender.sendMessage(configManager.getMessage("player_not_found", "%player%", args[1]));
                            configManager.playSound(playerSender, "action_error");
                            return;
                        }
                        sender.sendMessage(configManager.getMessage("admin_view_opening", "%player%", known.name()));
                        guiManager.openMailboxGUI(playerSender, known.uuid(), 1);
                    });
                    break;
                }
                OfflinePlayer targetPlayerView = Bukkit.getOfflinePlayer(args[1]);
                if (!targetPlayerView.hasPlayedBefore() && !targetPlayerView.isOnline()) {
                    sender.sendMessage(configManager.getMessage("player_not_found", "%player%", args[1]));
                    if (playerSender != null) configManager.playSound(playerSender, "action_error");
                    return true;
                }
                sender.sendMessage(configManager.getMessage("admin_view_opening", "%player%", targetPlayerView.getName() != null ? targetPlayerView.getName() : "Inconnu"));
                guiManager.openMailboxGUI(playerSender, targetPlayerView.getUniqueId(), 1);
                break;

            case "spy":
                if (!sender.hasPermission("shmails.admin.spy")) {
                    sender.sendMessage(configManager.getMessage("no_permission"));
                    if (playerSender != null) configManager.playSound(playerSender, "action_error");
                    return true;
                }
                if (playerSender == null) {
                    sender.sendMessage(configManager.getMessage("must_be_player"));
                    return true;
                }

                UUID spyUUID = playerSender.getUniqueId();
                if (plugin.isSpying(spyUUID)) { // Utilise la nouvelle méthode
                    plugin.removeSpy(spyUUID);   // Utilise la nouvelle méthode
                    playerSender.sendMessage(configManager.getMessage("admin_spy_off"));
                } else {
                    plugin.addSpy(spyUUID);      // Utilise la nouvelle méthode
                    playerSender.sendMessage(configManager.getMessage("admin_spy_on"));
                }
                configManager.playSound(playerSender, "gui_click_button");
                break;

            case "reload":
                // ... (inchangé) ...
                if (!sender.hasPermission("shmails.admin.reload")) {
                    sender.sendMessage(configManager.getMessage("no_permission"));
                    if (playerSender != null) configManager.playSound(playerSender, "action_error");
                    return true;
                }
                plugin.loadSpies(); // Recharger les spies en plus de la config principale
                configManager.reloadConfig();
                sender.sendMessage(configManager.getMessage("admin_config_reloaded"));
                if (playerSender != null) configManager.playSound(playerSender, "gui_click_button");
                plugin.getLogger().info("Configuration and spies reloaded by " + sender.getName());
                break;

            case "clear":
                // ... (inchangé) ...
                if (!sender.hasPermission("shmails.admin.clear")) {
                    sender.sendMessage(configManager.getMessage("no_permission"));
                    if (playerSender != null) configManager.playSound(playerSender, "action_error");
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(configManager.getPrefix() + "&cUsage: /" + label + " clear <joueur>");
                    if (playerSender != null) configManager.playSound(playerSender, "action_error");
                    return true;
                }
                if (plugin.getNetwork() != null) {
                    plugin.getNetwork().resolve(args[1], known -> {
                        if (known == null) {
                            sender.sendMessage(configManager.getMessage("player_not_found", "%player%", args[1]));
                            if (playerSender != null) configManager.playSound(playerSender, "action_error");
                            return;
                        }
                        mailManager.deleteAllMailsForPlayer(known.uuid());
                        sender.sendMessage(configManager.getMessage("admin_cleared_mails_all", "%player%", known.name()));
                        if (playerSender != null) configManager.playSound(playerSender, "mail_deleted");
                    });
                    break;
                }
                OfflinePlayer playerToClear = Bukkit.getOfflinePlayer(args[1]);
                if (!playerToClear.hasPlayedBefore() && !playerToClear.isOnline()) {
                    sender.sendMessage(configManager.getMessage("player_not_found", "%player%", args[1]));
                    if (playerSender != null) configManager.playSound(playerSender, "action_error");
                    return true;
                }

                mailManager.deleteAllMailsForPlayer(playerToClear.getUniqueId());
                String playerName = playerToClear.getName() != null ? playerToClear.getName() : "Inconnu";
                sender.sendMessage(configManager.getMessage("admin_cleared_mails_all", "%player%", playerName));

                if (playerSender != null) configManager.playSound(playerSender, "mail_deleted");
                break;


            case "import":
                if (!sender.hasPermission("shmails.admin.import")) {
                    sender.sendMessage(configManager.getMessage("no_permission"));
                    if (playerSender != null) configManager.playSound(playerSender, "action_error");
                    return true;
                }
                importLocalData(sender);
                break;

            default:
                sendAdminHelp(sender, label);
                break;
        }
        return true;
    }

    private final java.util.concurrent.atomic.AtomicBoolean importing = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * Mode réseau : reprend en base partagée les mails (SQLite ou YAML) et les espions (spies.yml) de ce serveur.
     * À faire une fois, sur le serveur qui a les mails ; un mail déjà en base n'est pas dupliqué.
     */
    private void importLocalData(CommandSender sender) {
        NetworkMails network = plugin.getNetwork();
        if (network == null) {
            sender.sendMessage(configManager.getMessage("admin_import_unavailable"));
            return;
        }
        if (!importing.compareAndSet(false, true)) {
            sender.sendMessage(configManager.getMessage("admin_import_running"));
            return;
        }
        sender.sendMessage(configManager.getMessage("admin_import_start"));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<org.Cry_Legende.sHMails.model.Mail> mails = mailManager.readAllLocalMails();
                List<UUID> spies = plugin.readLocalSpies();
                int added = network.importAll(mails, spies);
                sender.sendMessage(configManager.getMessage("admin_import_done", "%lus%", String.valueOf(mails.size()),
                    "%ajoutes%", String.valueOf(added), "%espions%", String.valueOf(spies.size())));
            } catch (java.sql.SQLException e) {
                plugin.getLogger().severe("Reprise des mails impossible : " + e.getMessage());
                sender.sendMessage(configManager.getMessage("error_occurred"));
            } finally {
                importing.set(false);
            }
        });
    }

    private void sendAdminHelp(CommandSender sender, String label) {
        // ... (inchangé) ...
        String cmdPrefix = (sender instanceof Player ? "/" : "") + label + " ";
        sender.sendMessage(configManager.getPrefix() + "&6Commandes d'administration SHMails:");
        sender.sendMessage(configManager.getPrefix() + "&e" + cmdPrefix + "view <joueur> &7- Voir les mails d'un joueur.");
        sender.sendMessage(configManager.getPrefix() + "&e" + cmdPrefix + "spy &7- Activer/Désactiver l'espionnage des mails.");
        sender.sendMessage(configManager.getPrefix() + "&e" + cmdPrefix + "reload &7- Recharger la configuration.");
        sender.sendMessage(configManager.getPrefix() + "&e" + cmdPrefix + "clear <joueur> &7- Supprimer tous les mails d'un joueur.");
        sender.sendMessage(configManager.getPrefix() + "&e" + cmdPrefix + "import &7- Mode réseau : reprendre en base les mails de ce serveur.");
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        // ... (inchangé) ...
        if (!sender.hasPermission("shmails.admin")) {
            return null;
        }

        List<String> completions = new ArrayList<>();
        List<String> subCommands = new ArrayList<>(Arrays.asList("view", "spy", "reload", "clear", "import"));

        if (args.length == 1) {
            StringUtil.copyPartialMatches(args[0], subCommands, completions);
        } else if (args.length == 2) {
            if (args[0].equalsIgnoreCase("view") || args[0].equalsIgnoreCase("clear")) {
                List<String> playerNames = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.toList());
                StringUtil.copyPartialMatches(args[1], playerNames, completions);
            }
        }
        completions.sort(String.CASE_INSENSITIVE_ORDER);
        return completions;
    }
}
