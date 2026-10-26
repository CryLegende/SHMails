package org.Cry_Legende.sHMails.manager;

import org.Cry_Legende.sHMails.SHMails;
import org.Cry_Legende.sHMails.utils.ChatUtils;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public class ConfigManager {

    private final SHMails plugin;
    private FileConfiguration config;

    public ConfigManager(SHMails plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        reloadConfig();
    }

    public void reloadConfig() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
    }

    public String getPrefix() {
        return ChatUtils.color(config.getString("plugin_prefix", "&7[&bSHMails&7] "));
    }

    public String getMessage(String path, String... replacements) {
        String message = config.getString("messages." + path);
        if (message == null) {
            plugin.getLogger().warning("Message not found in config: messages." + path);
            message = "&cMissing message: messages." + path;
        }
        for (int i = 0; i < replacements.length; i += 2) {
            if (i + 1 < replacements.length) {
                message = message.replace(replacements[i], replacements[i + 1]);
            }
        }
        return getPrefix() + ChatUtils.color(message);
    }

    public String getRawMessage(String path, String... replacements) {
        String message = config.getString(path); // Changed to directly use path for gui items
        if (message == null) {
            // Fallback for older structure if gui items are under messages.gui.
            message = config.getString("messages." + path);
            if (message == null) {
                plugin.getLogger().warning("Raw message/GUI item string not found in config: " + path + " or messages." + path);
                message = "&cMissing string: " + path;
            }
        }
        for (int i = 0; i < replacements.length; i += 2) {
            if (i + 1 < replacements.length) {
                message = message.replace(replacements[i], replacements[i + 1]);
            }
        }
        return ChatUtils.color(message);
    }

    public List<String> getFormattedLore(String path, String... replacements) {
        List<String> lore = config.getStringList(path);
        if (lore.isEmpty() && config.contains("messages." + path)) { // Fallback for older structure
            lore = config.getStringList("messages." + path);
        }
        if (lore.isEmpty()) {
            plugin.getLogger().warning("Lore not found in config for path: " + path);
            // return Collections.singletonList(ChatUtils.color("&cMissing lore: " + path));
        }

        List<String> coloredLore = lore.stream().map(ChatUtils::color).collect(Collectors.toList());
        for (int i = 0; i < replacements.length; i += 2) {
            if (i + 1 < replacements.length) {
                String placeholder = replacements[i];
                String value = replacements[i + 1];
                coloredLore.replaceAll(s -> s.replace(placeholder, value));
            }
        }
        return coloredLore;
    }


    public int getMaxMailsPerPlayer() {
        return config.getInt("max_mails_per_player", 30);
    }

    public int getLoginNotificationDelayTicks() {
        return config.getInt("login_notification_delay_ticks", 60);
    }

    public int getMaxMessageLineLength() {
        return config.getInt("max_message_line_length", 35);
    }

    public String getDateFormat() {
        return config.getString("date_format", "dd/MM/yyyy 'à' HH:mm");
    }

    public String getStorageType() {
        return config.getString("storage_type", "sqlite").toLowerCase();
    }

    public void playSound(Player player, String soundKey) {
        String soundString = config.getString("sounds." + soundKey, "");
        if (player == null || !player.isOnline() || soundString == null || soundString.isEmpty()) {
            return;
        }
        try {
            String[] parts = soundString.split(";");
            Sound sound = sound(parts[0]);
            if (sound == null) {
                throw new IllegalArgumentException("unknown sound " + parts[0]);
            }
            float volume = (parts.length > 1) ? Float.parseFloat(parts[1]) : 1.0f;
            float pitch = (parts.length > 2) ? Float.parseFloat(parts[2]) : 1.0f;
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid sound configuration for key '" + soundKey + "': " + soundString + " - " + e.getMessage());
        }
    }

    /** Son lu en config : ancien nom (ENTITY_PLAYER_LEVELUP) ou clé (minecraft:entity.player.levelup). */
    static Sound sound(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.indexOf(':') >= 0 || s.indexOf('.') >= 0) {
            NamespacedKey key = NamespacedKey.fromString(s.toLowerCase(Locale.ROOT));
            return key == null ? null : Registry.SOUND_EVENT.get(key);
        }
        try {
            return (Sound) Sound.class.getField(s.toUpperCase(Locale.ROOT)).get(null);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    public String getGuiTitle(int page, int maxPages) {
        return getRawMessage("gui.title", // Utilise getRawMessage car c'est un chemin direct
            "%page%", String.valueOf(page),
            "%max_pages%", String.valueOf(maxPages));
    }

    public String getAdminGuiTitlePrefix() {
        return getRawMessage("messages.admin_mails_view_title_prefix");
    }

    public int getGuiSize() {
        return config.getInt("gui.size", 54);
    }

    public List<Integer> getMailSlots() {
        List<Integer> slots = new ArrayList<>();
        String slotsString = config.getString("gui.mail_slots", "11-15,20-24,29-33");
        if (slotsString == null || slotsString.isEmpty()) return slots;

        for (String part : slotsString.split(",")) {
            part = part.trim();
            if (part.contains("-")) {
                String[] range = part.split("-");
                try {
                    int start = Integer.parseInt(range[0].trim());
                    int end = Integer.parseInt(range[1].trim());
                    for (int i = start; i <= end; i++) {
                        slots.add(i);
                    }
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("Invalid slot range in gui.mail_slots: " + part);
                }
            } else {
                try {
                    slots.add(Integer.parseInt(part.trim()));
                } catch (NumberFormatException e) {
                    plugin.getLogger().warning("Invalid slot number in gui.mail_slots: " + part);
                }
            }
        }
        return slots;
    }

    public ConfigurationSection getGuiItemConfigSection(String itemKey) { // Renommé pour clarté
        return config.getConfigurationSection("gui." + itemKey);
    }

    public boolean isDeleteAllMailsButtonEnabled() { // Renommé
        return config.getBoolean("gui.delete_all_mails_item.enabled", true);
    }

    public boolean isFillerEnabled() {
        return config.getBoolean("gui.filler_item.enabled", true);
    }

    public Material getFillerMaterial() {
        String matName = config.getString("gui.filler_item.material", "BLACK_STAINED_GLASS_PANE").toUpperCase();
        Material mat = Material.matchMaterial(matName);
        if (mat == null) {
            plugin.getLogger().warning("Invalid filler material: " + matName + ". Defaulting to BLACK_STAINED_GLASS_PANE.");
            return Material.BLACK_STAINED_GLASS_PANE;
        }
        return mat;
    }

    public String getFillerName() {
        return getRawMessage("gui.filler_item.name");
    }

    public boolean isInfoButtonEnabled() {
        return config.getBoolean("gui.info_button_item.enabled", true);
    }
}
