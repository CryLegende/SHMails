package org.Cry_Legende.sHMails.commands;

import org.Cry_Legende.sHMails.SHMails;
import org.Cry_Legende.sHMails.manager.ConfigManager;
import org.Cry_Legende.sHMails.manager.GUIManager;
import org.Cry_Legende.sHMails.manager.MailManager;
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


            default:
                sendAdminHelp(sender, label);
                break;
        }
        return true;
    }

    private void sendAdminHelp(CommandSender sender, String label) {
        // ... (inchangé) ...
        String cmdPrefix = (sender instanceof Player ? "/" : "") + label + " ";
        sender.sendMessage(configManager.getPrefix() + "&6Commandes d'administration SHMails:");
        sender.sendMessage(configManager.getPrefix() + "&e" + cmdPrefix + "view <joueur> &7- Voir les mails d'un joueur.");
        sender.sendMessage(configManager.getPrefix() + "&e" + cmdPrefix + "spy &7- Activer/Désactiver l'espionnage des mails.");
        sender.sendMessage(configManager.getPrefix() + "&e" + cmdPrefix + "reload &7- Recharger la configuration.");
        sender.sendMessage(configManager.getPrefix() + "&e" + cmdPrefix + "clear <joueur> &7- Supprimer tous les mails d'un joueur.");
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        // ... (inchangé) ...
        if (!sender.hasPermission("shmails.admin")) {
            return null;
        }

        List<String> completions = new ArrayList<>();
        List<String> subCommands = new ArrayList<>(Arrays.asList("view", "spy", "reload", "clear"));

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
