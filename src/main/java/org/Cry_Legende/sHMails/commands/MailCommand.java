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
import java.util.stream.Collectors;

public class MailCommand implements CommandExecutor, TabCompleter {

    private final SHMails plugin;
    private final ConfigManager configManager;
    private final MailManager mailManager;
    private final GUIManager guiManager;

    public MailCommand(SHMails plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.mailManager = plugin.getMailManager();
        this.guiManager = plugin.getGuiManager();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(configManager.getMessage("must_be_player"));
            return true;
        }
        Player player = (Player) sender;

        if (args.length == 0) { // /mail -> Ouvre le GUI
            if (!player.hasPermission("shmails.use")) {
                player.sendMessage(configManager.getMessage("no_permission"));
                configManager.playSound(player, "action_error");
                return true;
            }
            player.sendMessage(configManager.getMessage("mail_open_gui")); // Message d'ouverture
            guiManager.openMailboxGUI(player, player.getUniqueId(), 1);
            return true;
        }

        // /mail <joueur> <message>
        if (args.length >= 2) {
            if (!player.hasPermission("shmails.send")) {
                player.sendMessage(configManager.getMessage("no_permission"));
                configManager.playSound(player, "action_error");
                return true;
            }

            OfflinePlayer recipient = Bukkit.getOfflinePlayer(args[0]); // getOfflinePlayer est OK ici
            if (!recipient.hasPlayedBefore() && !recipient.isOnline()) {
                player.sendMessage(configManager.getMessage("player_not_found", "%player%", args[0]));
                configManager.playSound(player, "action_error");
                return true;
            }

            if (recipient.getUniqueId().equals(player.getUniqueId())) {
                player.sendMessage(configManager.getMessage("cannot_mail_self"));
                configManager.playSound(player, "action_error");
                return true;
            }

            // Vérification si la boîte du destinataire est pleine (la logique est maintenant dans MailManager.sendMail)
            // On ne vérifie plus le coût ici

            StringBuilder messageBuilder = new StringBuilder();
            for (int i = 1; i < args.length; i++) {
                messageBuilder.append(args[i]).append(i == args.length - 1 ? "" : " ");
            }
            String messageContent = messageBuilder.toString();

            mailManager.sendMail(player.getUniqueId(), player.getName(), recipient.getUniqueId(), messageContent);
            // Les messages de succès/erreur et sons sont gérés dans MailManager.sendMail pour être asynchrones
            return true;
        }

        player.sendMessage(configManager.getMessage("mail_usage"));
        configManager.playSound(player, "action_error");
        return true;
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("shmails.send") && args.length > 0) { // S'ils n'ont pas la perm d'envoyer, pas de suggestion de joueur
            return null;
        }

        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            // Suggérer les noms des joueurs en ligne
            String partialName = args[0].toLowerCase();
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (onlinePlayer.getName().toLowerCase().startsWith(partialName)) {
                    if (sender instanceof Player && onlinePlayer.getUniqueId().equals(((Player) sender).getUniqueId())) {
                        continue; // Ne pas se suggérer soi-même
                    }
                    completions.add(onlinePlayer.getName());
                }
            }
            StringUtil.copyPartialMatches(args[0], Arrays.asList(Bukkit.getOnlinePlayers().stream().map(Player::getName).toArray(String[]::new)), completions);
            completions.sort(String.CASE_INSENSITIVE_ORDER);
            return completions;
        }
        // Aucune complétion pour le message lui-même (args.length >= 2)
        return null;
    }
}