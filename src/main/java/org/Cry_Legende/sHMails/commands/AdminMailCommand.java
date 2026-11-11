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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * /shmails (alias shmailadmin, shma) : racine d'administration de SHMails. view, spy, clear, import et reload ont
 * chacune leur permission shmails.admin.&lt;sous-commande&gt;. Un expéditeur qui n'a aucun droit d'administration
 * obtient exactement le comportement de /mail, comme quand /shmails n'était qu'un alias de /mail.
 */
public class AdminMailCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("view", "spy", "clear", "import", "reload");
    private static final String PERMISSION_PREFIX = "shmails.admin.";

    private final SHMails plugin;
    private final ConfigManager configManager;
    private final GUIManager guiManager;
    private final MailManager mailManager;
    private final MailCommand mailCommand;

    public AdminMailCommand(SHMails plugin, MailCommand mailCommand) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.guiManager = plugin.getGuiManager();
        this.mailManager = plugin.getMailManager();
        this.mailCommand = mailCommand;
    }

    private static boolean can(CommandSender sender, String subCommand) {
        return sender.hasPermission(PERMISSION_PREFIX + subCommand);
    }

    /** Vrai si l'expéditeur a au moins un droit d'administration de SHMails. */
    private static boolean hasAnyAdmin(CommandSender sender) {
        return SUBCOMMANDS.stream().anyMatch(sub -> can(sender, sub)) || sender.hasPermission("shmails.admin.view.interact");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!hasAnyAdmin(sender)) {
            // Comme avant : /shmails était un alias de /mail
            return mailCommand.onCommand(sender, command, label, args);
        }

        String subCommand = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (!SUBCOMMANDS.contains(subCommand) || !can(sender, subCommand)) {
            sendUsage(sender);
            return true;
        }
        Player playerSender = (sender instanceof Player) ? (Player) sender : null;

        switch (subCommand) {
            case "view":
                if (playerSender == null) {
                    sender.sendMessage(configManager.getMessage("must_be_player"));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(configManager.getMessage("admin_usage_view"));
                    configManager.playSound(playerSender, "action_error");
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
                    configManager.playSound(playerSender, "action_error");
                    return true;
                }
                sender.sendMessage(configManager.getMessage("admin_view_opening", "%player%", targetPlayerView.getName() != null ? targetPlayerView.getName() : "Inconnu"));
                guiManager.openMailboxGUI(playerSender, targetPlayerView.getUniqueId(), 1);
                break;

            case "spy":
                if (playerSender == null) {
                    sender.sendMessage(configManager.getMessage("must_be_player"));
                    return true;
                }

                UUID spyUUID = playerSender.getUniqueId();
                if (plugin.isSpying(spyUUID)) {
                    plugin.removeSpy(spyUUID);
                    playerSender.sendMessage(configManager.getMessage("admin_spy_off"));
                } else {
                    plugin.addSpy(spyUUID);
                    playerSender.sendMessage(configManager.getMessage("admin_spy_on"));
                }
                configManager.playSound(playerSender, "gui_click_button");
                break;

            case "reload":
                if (plugin.reload(sender)) {
                    sender.sendMessage(configManager.getMessage("admin_config_reloaded"));
                    sender.sendMessage(configManager.getMessage("admin_reload_note"));
                    if (playerSender != null) configManager.playSound(playerSender, "gui_click_button");
                    plugin.getLogger().info("Configuration reloaded by " + sender.getName());
                }
                break;

            case "clear":
                if (args.length < 2) {
                    sender.sendMessage(configManager.getMessage("admin_usage_clear"));
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
                importLocalData(sender);
                break;

            default:
                sendUsage(sender);
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

    /** Une seule ligne d'usage, limitée aux sous-commandes que l'expéditeur peut utiliser. */
    private void sendUsage(CommandSender sender) {
        List<String> allowed = SUBCOMMANDS.stream().filter(sub -> can(sender, sub)).collect(Collectors.toList());
        if (allowed.isEmpty()) {
            sender.sendMessage(configManager.getMessage("no_permission"));
            return;
        }
        sender.sendMessage(configManager.getMessage("admin_usage", "%subcommands%", String.join("|", allowed)));
    }

    @Nullable
    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!hasAnyAdmin(sender)) {
            return mailCommand.onTabComplete(sender, command, alias, args);
        }

        List<String> completions = new ArrayList<>();
        if (args.length == 1) {
            List<String> subCommands = SUBCOMMANDS.stream().filter(sub -> can(sender, sub)).collect(Collectors.toList());
            StringUtil.copyPartialMatches(args[0], subCommands, completions);
        } else if (args.length == 2) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if ((sub.equals("view") || sub.equals("clear")) && can(sender, sub)) {
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
