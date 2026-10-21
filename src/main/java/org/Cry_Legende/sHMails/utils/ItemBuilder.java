package org.Cry_Legende.sHMails.utils;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ItemBuilder {
    private ItemStack itemStack;
    private ItemMeta itemMeta;

    public ItemBuilder(Material material) {
        this.itemStack = new ItemStack(material);
        this.itemMeta = itemStack.getItemMeta();
    }

    public ItemBuilder(ItemStack itemStack) {
        this.itemStack = itemStack.clone(); // Important de cloner pour éviter les modifications partagées
        this.itemMeta = this.itemStack.getItemMeta();
    }

    public ItemBuilder setAmount(int amount) {
        itemStack.setAmount(amount);
        return this;
    }

    public ItemBuilder setName(String name) {
        itemMeta.setDisplayName(ChatUtils.color(name));
        return this;
    }

    public ItemBuilder setLore(List<String> lore) {
        itemMeta.setLore(ChatUtils.color(lore));
        return this;
    }

    public ItemBuilder setLore(String... lore) {
        itemMeta.setLore(ChatUtils.color(Arrays.asList(lore)));
        return this;
    }

    public ItemBuilder addLoreLine(String line) {
        List<String> lore = itemMeta.getLore();
        if (lore == null) {
            lore = new java.util.ArrayList<>();
        }
        lore.add(ChatUtils.color(line));
        itemMeta.setLore(lore);
        return this;
    }

    // Pour des NBT Tags si besoin plus tard (ex: stocker l'ID du mail dans l'item)
    // public ItemBuilder setNBTString(Plugin plugin, String key, String value) {
    //    NamespacedKey nsk = new NamespacedKey(plugin, key);
    //    itemMeta.getPersistentDataContainer().set(nsk, PersistentDataType.STRING, value);
    //    return this;
    // }

    public ItemStack build() {
        itemStack.setItemMeta(itemMeta);
        return itemStack;
    }
}
