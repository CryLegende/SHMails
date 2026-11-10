package org.Cry_Legende.sHMails.listeners;

import org.Cry_Legende.sHMails.SHMails;
import org.Cry_Legende.sHMails.manager.ConfigManager;
import org.Cry_Legende.sHMails.manager.GUIManager;
import org.Cry_Legende.sHMails.manager.MailManager;
import org.Cry_Legende.sHMails.network.NetworkMails;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;

public class PlayerJoinListener implements Listener {

    private final SHMails plugin;
    private final MailManager mailManager;
    private final ConfigManager configManager;
    private final GUIManager guiManager;


    public PlayerJoinListener(SHMails plugin) {
        this.plugin = plugin;
        this.mailManager = plugin.getMailManager();
        this.configManager = plugin.getConfigManager();
        this.guiManager = plugin.getGuiManager();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAsyncPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        // En réseau, la boîte est lue à l'arrivée, une fois le départ de l'autre serveur confirmé (voir onPlayerJoin)
        if (plugin.getNetwork() == null && event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            mailManager.getPlayerMails(event.getUniqueId()); // Pré-charge dans le cache
        }
    }


    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        NetworkMails network = plugin.getNetwork();
        if (network != null) {
            network.joined(player, count -> notifyMails(player, count));
            return;
        }
        // Les données devraient être en cache. Si ce n'est pas le cas, MailManager les chargera à la demande.

        int mailCount = mailManager.getMailCount(player.getUniqueId()); // Nombre total de mails
        notifyMails(player, mailCount);
    }

    /** Notification de connexion : nombre de mails dans la boîte, après le délai de la configuration. */
    private void notifyMails(Player player, int mailCount) {
        if (configManager.getLoginNotificationDelayTicks() >= 0) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    if (player.isOnline()) { // Vérifier si toujours en ligne
                        if (mailCount > 0) {
                            player.sendMessage(configManager.getMessage("mail_received_while_offline", "%count%", String.valueOf(mailCount)));
                            configManager.playSound(player, "mail_received");
                        } else {
                            // player.sendMessage(configManager.getMessage("no_new_mails_on_login")); // Optionnel
                        }
                    }
                }
            }.runTaskLater(plugin, Math.max(0, configManager.getLoginNotificationDelayTicks()));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        mailManager.unloadPlayerData(player.getUniqueId());
        guiManager.clearViewerContext(player.getUniqueId());
        plugin.getMailSpies().remove(player.getUniqueId());

        // Nettoyer le mode réponse si le joueur quitte
        plugin.replyingToOriginalMailId.remove(player.getUniqueId());
        plugin.replyingToSenderUUID.remove(player.getUniqueId());
        plugin.replyingToSenderName.remove(player.getUniqueId());
    }
}