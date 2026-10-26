package org.Cry_Legende.sHMails.listeners;

import org.Cry_Legende.sHMails.SHMails;
import org.Cry_Legende.sHMails.manager.ConfigManager;
import org.Cry_Legende.sHMails.manager.MailManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent; // Utiliser AsyncPlayerChatEvent

import java.util.UUID;

public class PlayerChatListener implements Listener {

    private final SHMails plugin;
    private final MailManager mailManager;
    private final ConfigManager configManager;

    public PlayerChatListener(SHMails plugin) {
        this.plugin = plugin;
        this.mailManager = plugin.getMailManager();
        this.configManager = plugin.getConfigManager();
    }

    @EventHandler(priority = EventPriority.LOW) // LOW pour intercepter avant d'autres plugins de chat potentiels
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();

        if (plugin.replyingToOriginalMailId.containsKey(playerId)) {
            event.setCancelled(true); // Empêche le message d'apparaître dans le chat public

            String messageContent = event.getMessage();
            UUID originalMailId = plugin.replyingToOriginalMailId.get(playerId);
            UUID originalSenderUUID = plugin.replyingToSenderUUID.get(playerId);
            String originalSenderName = plugin.replyingToSenderName.get(playerId);

            // Nettoyer immédiatement pour éviter double traitement ou problèmes si le joueur re-chatte vite
            plugin.replyingToOriginalMailId.remove(playerId);
            plugin.replyingToSenderUUID.remove(playerId);
            plugin.replyingToSenderName.remove(playerId);

            // Thread du chat : la suite (cache des mails, sons) se fait sur le thread principal.
            plugin.getServer().getScheduler().runTask(plugin, () ->
                handleReply(player, messageContent, originalMailId, originalSenderUUID, originalSenderName));
        }
    }

    private void handleReply(Player player, String messageContent, UUID originalMailId, UUID originalSenderUUID, String originalSenderName) {
        if (!player.isOnline()) return;
        UUID playerId = player.getUniqueId();

        if (messageContent.equalsIgnoreCase("cancel") || messageContent.equalsIgnoreCase("annuler")) {
            player.sendMessage(configManager.getMessage("reply_cancelled"));
            configManager.playSound(player, "reply_mode_cancel");
            return;
        }

        if (originalSenderUUID == null || originalSenderName == null) {
            player.sendMessage(configManager.getMessage("error_occurred")); // Erreur interne
            configManager.playSound(player, "action_error");
            return;
        }

        // Envoyer le nouveau mail (la réponse). Le mail original n'est supprimé (de la boîte de l'utilisateur actuel)
        // qu'une fois la réponse remise : si la boîte du destinataire est pleine, il reste.
        mailManager.sendMail(playerId, player.getName(), originalSenderUUID, messageContent, () -> {
            mailManager.deleteMail(playerId, originalMailId); // playerId est le propriétaire du mail original
            if (player.isOnline()) {
                player.sendMessage(configManager.getMessage("reply_message_sent", "%recipient%", originalSenderName));
            }
        });
        // Le son "mail_sent" est déjà joué par mailManager.sendMail
    }
}