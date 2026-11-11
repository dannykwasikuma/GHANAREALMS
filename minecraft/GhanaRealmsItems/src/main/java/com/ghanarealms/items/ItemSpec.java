package com.ghanarealms.items;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.List;

/**
 * Builds an ItemStack from the catalogue spec string - version-proof (Bukkit API, no item-component
 * command syntax). Format: MATERIAL AMOUNT [name=Some_Name] [ench=SHARPNESS:5,MENDING:1] [potion=STRONG_HEALING] [lore=Text_here]
 * Underscores in name/lore become spaces.
 */
public final class ItemSpec {
    private ItemSpec() {}

    public static ItemStack parse(String spec) throws IllegalArgumentException {
        String[] t = spec.trim().split("\\s+");
        if (t.length < 1) throw new IllegalArgumentException("empty spec");
        Material m = Material.matchMaterial(t[0]);
        if (m == null || !m.isItem()) throw new IllegalArgumentException("unknown item " + t[0] + " for this server version");
        int amount = t.length > 1 && t[1].matches("\\d+") ? Math.max(1, Integer.parseInt(t[1])) : 1;
        ItemStack it = new ItemStack(m, amount);
        ItemMeta meta = it.getItemMeta();
        for (int i = 1; i < t.length; i++) {
            if (!t[i].contains("=")) continue;
            String key = t[i].substring(0, t[i].indexOf('=')).toLowerCase();
            String val = t[i].substring(t[i].indexOf('=') + 1);
            switch (key) {
                case "name" -> meta.displayName(Component.text(val.replace('_', ' ')).color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
                case "lore" -> meta.lore(List.of(Component.text(val.replace('_', ' ')).color(NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
                case "ench" -> {
                    for (String e : val.split(",")) {
                        String[] p = e.split(":");
                        Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(p[0].toLowerCase()));
                        if (ench == null) throw new IllegalArgumentException("unknown enchantment " + p[0] + " for this server version");
                        int lvl = p.length > 1 ? Integer.parseInt(p[1]) : 1;
                        if (meta instanceof EnchantmentStorageMeta esm) esm.addStoredEnchant(ench, lvl, true);
                        else meta.addEnchant(ench, lvl, true);
                    }
                }
                case "potion" -> {
                    if (!(meta instanceof PotionMeta pm)) throw new IllegalArgumentException("potion= on a non-potion item");
                    pm.setBasePotionType(PotionType.valueOf(val.toUpperCase()));
                }
                default -> { /* ignore unknown keys */ }
            }
        }
        it.setItemMeta(meta);
        return it;
    }
}
