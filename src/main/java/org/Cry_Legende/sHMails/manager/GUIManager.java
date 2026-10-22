package org.Cry_Legende.sHMails.manager;

import org.Cry_Legende.sHMails.SHMails;
import org.Cry_Legende.sHMails.model.Mail;
import org.Cry_Legende.sHMails.utils.ChatUtils;
import org.Cry_Legende.sHMails.utils.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class GUIManager {

    private final SHMails plugin;
    private final ConfigManager configManager;
    private final MailManager mailManager;

    public final HashMap<UUID, Integer> currentPlayerPages = new HashMap<>();
    public final HashMap<UUID, UUID> viewingMailboxOwner = new HashMap<>();


    public GUIManager(SHMails plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.mailManager = plugin.getMailManager();
    }

    public void openMailboxGUI(Player viewer, UUID mailOwnerUUID, int page) {
        openMailboxGUI(viewer, mailOwnerUUID, page, true);
    }

    /**
     * @param reload true : relit les mails depuis la source avant d'afficher (ouverture, changement de page) ;
     *               false : affiche le cache tel quel. À utiliser juste après une suppression : le cache est déjà à jour,
     *               alors que la suppression en base part dans une tâche asynchrone indépendante, et une relecture
     *               lancée en même temps peut la devancer, rendre l'ancienne liste et réafficher le mail supprimé.
     */
    public void openMailboxGUI(Player viewer, UUID mailOwnerUUID, int page, boolean reload) {
        OfflinePlayer mailOwner = Bukkit.getOfflinePlayer(mailOwnerUUID);
        if (!mailOwner.hasPlayedBefore() && !mailOwner.isOnline()) {
            viewer.sendMessage(configManager.getMessage("player_not_found", "%player%", mailOwnerUUID.toString()));
            configManager.playSound(viewer, "action_error");
            return;
        }

        configManager.playSound(viewer, "gui_open");

        Runnable render = () -> {
            List<Mail> mails = mailManager.getPlayerMails(mailOwnerUUID);

            if (mails.isEmpty() && viewer.getUniqueId().equals(mailOwnerUUID)) {
                viewer.sendMessage(configManager.getMessage("no_mails"));
            }

            if (!viewer.getUniqueId().equals(mailOwnerUUID)) {
                viewingMailboxOwner.put(viewer.getUniqueId(), mailOwnerUUID);
            } else {
                viewingMailboxOwner.remove(viewer.getUniqueId());
            }

            List<Integer> mailSlots = configManager.getMailSlots();
            if (mailSlots.isEmpty()) {
                plugin.getLogger().severe("No mail slots defined in config.yml (gui.mail_slots)! GUI cannot be opened.");
                viewer.sendMessage(configManager.getMessage("error_occurred"));
                configManager.playSound(viewer, "action_error");
                return;
            }
            int itemsPerPage = mailSlots.size();
            int totalPages = Math.max(1, (int) Math.ceil((double) mails.size() / itemsPerPage));
            final int finalPage = Math.max(1, Math.min(page, totalPages));

            String title;
            if (!viewer.getUniqueId().equals(mailOwnerUUID)) {
                title = configManager.getAdminGuiTitlePrefix() + (mailOwner.getName() != null ? mailOwner.getName() : "Inconnu") +
                    " (" + finalPage + "/" + totalPages + ")";
            } else {
                title = configManager.getGuiTitle(finalPage, totalPages);
            }

            Inventory gui = Bukkit.createInventory(viewer, configManager.getGuiSize(), title);

            if (configManager.isFillerEnabled()) {
                Material fillerMat = configManager.getFillerMaterial();
                ItemStack filler = new ItemBuilder(fillerMat).setName(configManager.getFillerName()).build();
                for (int i = 0; i < gui.getSize(); i++) {
                    if (!mailSlots.contains(i) && !isControlSlot(i, configManager)) {
                        gui.setItem(i, filler);
                    }
                }
            }

            int startIndex = (finalPage - 1) * itemsPerPage;
            for (int i = 0; i < itemsPerPage; i++) {
                int mailIndex = startIndex + i;
                if (mailIndex < mails.size()) {
                    Mail mail = mails.get(mailIndex);
                    int slotToPlace = mailSlots.get(i);
                    if (slotToPlace < gui.getSize()) {
                        gui.setItem(slotToPlace, createMailItem(mail));
                    }
                }
            }

            addControlItems(gui, finalPage, totalPages, viewer.getUniqueId().equals(mailOwnerUUID));

            currentPlayerPages.put(viewer.getUniqueId(), finalPage);

            new BukkitRunnable() {
                @Override
                public void run() {
                    viewer.openInventory(gui);
                }
            }.runTask(plugin);
        };
        if (reload) {
            mailManager.loadPlayerDataAsync(mailOwnerUUID, render);
        } else {
            render.run(); // thread principal (clic) : le menu est ouvert au tick suivant par le runTask ci-dessus
        }
    }

    private static boolean isControlSlot(int slot, ConfigManager cfgManager) {
        ConfigurationSection prevPageConfig = cfgManager.getGuiItemConfigSection("previous_page_item");
        ConfigurationSection nextPageConfig = cfgManager.getGuiItemConfigSection("next_page_item");
        ConfigurationSection closeConfig = cfgManager.getGuiItemConfigSection("close_item");
        ConfigurationSection deleteAllConfig = cfgManager.getGuiItemConfigSection("delete_all_mails_item");
        ConfigurationSection infoButtonConfig = cfgManager.getGuiItemConfigSection("info_button_item"); // Nouveau

        if (prevPageConfig != null && slot == prevPageConfig.getInt("slot", -1)) return true;
        if (nextPageConfig != null && slot == nextPageConfig.getInt("slot", -1)) return true;
        if (closeConfig != null && slot == closeConfig.getInt("slot", -1)) return true;
        if (cfgManager.isDeleteAllMailsButtonEnabled() && deleteAllConfig != null && slot == deleteAllConfig.getInt("slot", -1))
            return true;
        if (cfgManager.isInfoButtonEnabled() && infoButtonConfig != null && slot == infoButtonConfig.getInt("slot", -1))
            return true; // Nouveau

        return false;
    }


    private ItemStack createMailItem(Mail mail) {
        String mailItemBaseKey = "gui.mail_item";

        Material material = Material.matchMaterial(configManager.getRawMessage(mailItemBaseKey + ".material", "PAPER").toUpperCase());
        if (material == null) {
            plugin.getLogger().warning("Invalid mail item material: " + configManager.getRawMessage(mailItemBaseKey + ".material") + ". Defaulting to PAPER.");
            material = Material.PAPER;
        }

        String name = configManager.getRawMessage(mailItemBaseKey + ".name",
            "%sender%", mail.getSenderName() != null ? mail.getSenderName() : "Inconnu"
        );

        List<String> baseLoreConfig = configManager.getFormattedLore(mailItemBaseKey + ".lore",
            "%date%", mailManager.formatDate(mail.getTimestamp())
            // "%message%" sera traité spécialement
        );

        List<String> finalLore = new ArrayList<>();
        List<String> formattedMessageLines = mailManager.formatMessageForLore(mail.getMessage())
            .stream()
            .map(line -> ChatUtils.color("  &f" + line)) // Préfixe pour l'indentation/couleur
            .collect(Collectors.toList());

        for (String loreLineFromConfig : baseLoreConfig) {
            if (loreLineFromConfig.contains("%message%")) {
                // Si la ligne contient %message%, remplacer cette ligne par toutes les lignes du message formaté
                finalLore.addAll(formattedMessageLines);
            } else {
                finalLore.add(loreLineFromConfig); // Ajouter les autres lignes du lore telles quelles
            }
        }
        // Si le placeholder %message% n'était pas dans le lore mais que la ligne "Message:" y était,
        // on pourrait avoir une logique pour insérer après cette ligne "Message:", mais le placeholder est plus direct.

        ItemBuilder itemBuilder = new ItemBuilder(material).setName(name).setLore(finalLore);

        ItemStack item = itemBuilder.build();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(
                new org.bukkit.NamespacedKey(plugin, "mail_id"),
                org.bukkit.persistence.PersistentDataType.STRING,
                mail.getMailId().toString()
            );
            item.setItemMeta(meta);
        }
        return item;
    }

    private void addControlItems(Inventory gui, int currentPage, int totalPages, boolean isOwnerViewing) {
        ConfigurationSection prevPageConfig = configManager.getGuiItemConfigSection("previous_page_item");
        if (prevPageConfig != null && currentPage > 1) {
            Material mat = Material.matchMaterial(prevPageConfig.getString("material", "ARROW"));
            if (mat == null) mat = Material.ARROW;
            gui.setItem(prevPageConfig.getInt("slot"), new ItemBuilder(mat)
                .setName(configManager.getRawMessage("gui.previous_page_item.name"))
                .setLore(configManager.getFormattedLore("gui.previous_page_item.lore", "%previous_page%", String.valueOf(currentPage - 1)))
                .build());
        }

        ConfigurationSection nextPageConfig = configManager.getGuiItemConfigSection("next_page_item");
        if (nextPageConfig != null && currentPage < totalPages) {
            Material mat = Material.matchMaterial(nextPageConfig.getString("material", "ARROW"));
            if (mat == null) mat = Material.ARROW;
            gui.setItem(nextPageConfig.getInt("slot"), new ItemBuilder(mat)
                .setName(configManager.getRawMessage("gui.next_page_item.name"))
                .setLore(configManager.getFormattedLore("gui.next_page_item.lore", "%next_page%", String.valueOf(currentPage + 1)))
                .build());
        }

        ConfigurationSection closeConfig = configManager.getGuiItemConfigSection("close_item");
        if (closeConfig != null) {
            Material closeMat = Material.matchMaterial(closeConfig.getString("material", "BARRIER"));
            if (closeMat == null) closeMat = Material.BARRIER;
            gui.setItem(closeConfig.getInt("slot"), new ItemBuilder(closeMat)
                .setName(configManager.getRawMessage("gui.close_item.name"))
                .setLore(configManager.getFormattedLore("gui.close_item.lore"))
                .build());
        }

        if (isOwnerViewing && configManager.isDeleteAllMailsButtonEnabled()) {
            ConfigurationSection deleteAllConfig = configManager.getGuiItemConfigSection("delete_all_mails_item");
            if (deleteAllConfig != null) {
                Material mat = Material.matchMaterial(deleteAllConfig.getString("material", "LAVA_BUCKET"));
                if (mat != null) { // Check if material is valid
                    gui.setItem(deleteAllConfig.getInt("slot"), new ItemBuilder(mat)
                        .setName(configManager.getRawMessage("gui.delete_all_mails_item.name"))
                        .setLore(configManager.getFormattedLore("gui.delete_all_mails_item.lore"))
                        .build());
                } else {
                    plugin.getLogger().warning("Invalid material for delete_all_mails_item: " + deleteAllConfig.getString("material") + ". Item not added.");
                }
            }
        }

        // Nouveau bouton d'information
        if (configManager.isInfoButtonEnabled()) {
            ConfigurationSection infoConfig = configManager.getGuiItemConfigSection("info_button_item");
            if (infoConfig != null) {
                String materialName = infoConfig.getString("material", "BOOK").toUpperCase();
                Material mat = Material.matchMaterial(materialName);
                if (mat == null) {
                    plugin.getLogger().warning("Invalid material for info_button_item: " + materialName + ". Defaulting to BOOK.");
                    mat = Material.BOOK;
                }
                int slot = infoConfig.getInt("slot", 53); // Slot 53 = en bas à droite pour un GUI de 54 slots

                gui.setItem(slot, new ItemBuilder(mat)
                    .setName(configManager.getRawMessage("gui.info_button_item.name"))
                    .setLore(configManager.getFormattedLore("gui.info_button_item.lore"))
                    .build());
            }
        }
    }

    public void clearViewerContext(UUID viewerId) {
        currentPlayerPages.remove(viewerId);
        viewingMailboxOwner.remove(viewerId);
    }
}
