package org.Cry_Legende.sHMails.listeners;

import org.Cry_Legende.sHMails.SHMails;
import org.Cry_Legende.sHMails.manager.ConfigManager;
import org.Cry_Legende.sHMails.manager.GUIManager;
import org.Cry_Legende.sHMails.manager.MailManager;
import org.Cry_Legende.sHMails.model.Mail;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.UUID;

public class InventoryClickListener implements Listener {

    private final SHMails plugin;
    private final ConfigManager configManager;
    private final MailManager mailManager;
    private final GUIManager guiManager;

    public InventoryClickListener(SHMails plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.mailManager = plugin.getMailManager();
        this.guiManager = plugin.getGuiManager();
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        String inventoryTitle = event.getView().getTitle();

        String expectedMailboxTitleStart = configManager.getGuiTitle(1, 1).substring(0, configManager.getGuiTitle(1, 1).indexOf("(")).trim();
        String expectedAdminTitleStart = configManager.getAdminGuiTitlePrefix().trim();

        if (inventoryTitle.startsWith(expectedMailboxTitleStart) || inventoryTitle.startsWith(expectedAdminTitleStart)) {
            // Ne pas jouer le son de fermeture si un autre inventaire SHMail est ouvert immédiatement (ex, changement de page)
            // C'est géré par le son d'ouverture du nouveau GUI.
            // Le clear du contexte se fait au clic sur "fermer" ou à la déconnexion.
            // configManager.playSound(player, "gui_close"); // Ce son est maintenant joué au clic sur le bouton fermer
        }
    }

    // Un item glissé dans la boîte aux lettres disparaîtrait à la fermeture du menu.
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (isMailGui(event.getView().getTitle())) {
            event.setCancelled(true);
        }
    }

    private boolean isMailGui(String inventoryTitle) {
        String expectedMailboxTitleStart = configManager.getGuiTitle(1, 1).substring(0, configManager.getGuiTitle(1, 1).indexOf("(")).trim();
        String expectedAdminTitleStart = configManager.getAdminGuiTitlePrefix().trim();
        return inventoryTitle.startsWith(expectedMailboxTitleStart) || inventoryTitle.startsWith(expectedAdminTitleStart);
    }


    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        ItemStack clickedItem = event.getCurrentItem();
        Inventory clickedInventory = event.getClickedInventory();

        if (isMailGui(event.getView().getTitle())) {
            // Annulé avant le test de case vide : un item posé dans le menu (clic ou touche numérique)
            // disparaîtrait à sa fermeture.
            event.setCancelled(true);
            if (clickedInventory == null || clickedItem == null || clickedItem.getType() == Material.AIR) return;

            UUID mailOwnerUUID = guiManager.viewingMailboxOwner.getOrDefault(player.getUniqueId(), player.getUniqueId());
            int currentPage = guiManager.currentPlayerPages.getOrDefault(player.getUniqueId(), 1);

            ConfigurationSection prevPageConfig = configManager.getGuiItemConfigSection("previous_page_item");
            ConfigurationSection nextPageConfig = configManager.getGuiItemConfigSection("next_page_item");
            ConfigurationSection closeConfig = configManager.getGuiItemConfigSection("close_item");
            ConfigurationSection deleteAllConfig = configManager.getGuiItemConfigSection("delete_all_mails_item"); // Nom changé

            String prevPageName = prevPageConfig != null ? configManager.getRawMessage("gui.previous_page_item.name") : "";
            String nextPageName = nextPageConfig != null ? configManager.getRawMessage("gui.next_page_item.name") : "";
            String closeName = closeConfig != null ? configManager.getRawMessage("gui.close_item.name") : "";
            String deleteAllName = deleteAllConfig != null ? configManager.getRawMessage("gui.delete_all_mails_item.name") : "";

            Material prevPageMat = prevPageConfig != null ? Material.matchMaterial(prevPageConfig.getString("material", "ARROW")) : null;
            Material nextPageMat = nextPageConfig != null ? Material.matchMaterial(nextPageConfig.getString("material", "ARROW")) : null;
            Material closeMat = closeConfig != null ? Material.matchMaterial(closeConfig.getString("material", "BARRIER")) : null;
            Material deleteAllMat = configManager.isDeleteAllMailsButtonEnabled() && deleteAllConfig != null ? Material.matchMaterial(deleteAllConfig.getString("material", "LAVA_BUCKET")) : null;

            ItemMeta clickedMeta = clickedItem.getItemMeta();
            if (clickedMeta == null) return; // Pas de méta, ne peut pas être un de nos items avec nom

            String clickedDisplayName = clickedMeta.getDisplayName();

            if (prevPageMat != null && clickedItem.getType() == prevPageMat && clickedDisplayName.equals(prevPageName)) {
                if (currentPage > 1) {
                    guiManager.openMailboxGUI(player, mailOwnerUUID, currentPage - 1);
                    configManager.playSound(player, "gui_click_button");
                }
            } else if (nextPageMat != null && clickedItem.getType() == nextPageMat && clickedDisplayName.equals(nextPageName)) {
                List<Mail> mails = mailManager.getPlayerMails(mailOwnerUUID);
                int itemsPerPage = configManager.getMailSlots().size();
                if (itemsPerPage == 0) itemsPerPage = 1; // Eviter division par zéro
                int totalPages = Math.max(1, (int) Math.ceil((double) mails.size() / itemsPerPage));
                if (currentPage < totalPages) {
                    guiManager.openMailboxGUI(player, mailOwnerUUID, currentPage + 1);
                    configManager.playSound(player, "gui_click_button");
                }
            } else if (closeMat != null && clickedItem.getType() == closeMat && clickedDisplayName.equals(closeName)) {
                player.closeInventory();
                guiManager.clearViewerContext(player.getUniqueId()); // Nettoyer le contexte ici
                configManager.playSound(player, "gui_close");
            } else if (deleteAllMat != null && clickedItem.getType() == deleteAllMat &&
                clickedDisplayName.equals(deleteAllName) &&
                player.getUniqueId().equals(mailOwnerUUID)) {
                mailManager.deleteAllMailsForPlayer(player.getUniqueId()); // Nom de méthode changé
                player.sendMessage(configManager.getMessage("mail_deleted")); // Message générique ou spécifique
                guiManager.openMailboxGUI(player, player.getUniqueId(), 1);
                configManager.playSound(player, "mail_deleted");
            } else {
                PersistentDataContainer container = clickedMeta.getPersistentDataContainer();
                org.bukkit.NamespacedKey mailIdKey = new org.bukkit.NamespacedKey(plugin, "mail_id");

                if (container.has(mailIdKey, PersistentDataType.STRING)) {
                    UUID mailId = UUID.fromString(container.get(mailIdKey, PersistentDataType.STRING));
                    Mail mail = mailManager.getMailById(mailOwnerUUID, mailId);

                    if (mail == null) {
                        player.sendMessage(configManager.getMessage("error_mail_not_found"));
                        configManager.playSound(player, "action_error");
                        guiManager.openMailboxGUI(player, mailOwnerUUID, currentPage);
                        return;
                    }

                    boolean isAdminViewing = !player.getUniqueId().equals(mailOwnerUUID);
                    if (isAdminViewing && !player.hasPermission("shmails.admin.view.interact")) {
                        player.sendMessage(configManager.getMessage("error_cannot_interact_adminview"));
                        configManager.playSound(player, "action_error");
                        return;
                    }
                    configManager.playSound(player, "gui_click_mail");

                    if (event.getClick() == ClickType.LEFT) { // Répondre
                        if (mail.getSenderUUID().equals(player.getUniqueId())) {
                            player.sendMessage(configManager.getMessage("reply_cannot_self"));
                            configManager.playSound(player, "action_error");
                            return;
                        }

                        plugin.replyingToOriginalMailId.put(player.getUniqueId(), mail.getMailId());
                        plugin.replyingToSenderUUID.put(player.getUniqueId(), mail.getSenderUUID());
                        plugin.replyingToSenderName.put(player.getUniqueId(), mail.getSenderName());

                        player.closeInventory();
                        guiManager.clearViewerContext(player.getUniqueId()); // Contexte fermé
                        player.sendMessage(configManager.getMessage("reply_enter_mode", "%sender%", mail.getSenderName()));
                        configManager.playSound(player, "reply_mode_enter");

                    } else if (event.getClick() == ClickType.RIGHT) { // Supprimer
                        mailManager.deleteMail(mailOwnerUUID, mailId);
                        player.sendMessage(configManager.getMessage("mail_deleted"));
                        configManager.playSound(player, "mail_deleted");
                        guiManager.openMailboxGUI(player, mailOwnerUUID, currentPage);
                    }
                    // Shift+Clic pour Lu/Non lu supprimé
                }
            }
        }
    }
}
