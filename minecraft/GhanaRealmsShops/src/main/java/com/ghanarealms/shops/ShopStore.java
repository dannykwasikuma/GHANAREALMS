package com.ghanarealms.shops;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public class ShopStore {

    public static class Shop {
        public UUID owner;
        public String ownerName;
        public Material material;
        public int quantity;
        public double buyPrice;   // price a customer pays to BUY from this shop (-1 = disabled)
        public double sellPrice;  // price the shop owner's chest pays to buy FROM a customer (-1 = disabled)
        public Location chestLocation;
    }

    private final JavaPlugin plugin;
    private final File file;
    private final Map<String, Shop> shops = new HashMap<>(); // keyed by "world,x,y,z" of the chest

    public ShopStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "shops.yml");
        load();
    }

    private String key(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    public Shop get(Location chestLoc) {
        return shops.get(key(chestLoc));
    }

    public void put(Shop shop) {
        shops.put(key(shop.chestLocation), shop);
        save();
    }

    public void remove(Location chestLoc) {
        shops.remove(key(chestLoc));
        save();
    }

    public Collection<Shop> all() {
        return shops.values();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        var section = cfg.getConfigurationSection("shops");
        if (section == null) return;
        for (String k : section.getKeys(false)) {
            var s = section.getConfigurationSection(k);
            Shop shop = new Shop();
            shop.owner = UUID.fromString(s.getString("owner"));
            shop.ownerName = s.getString("ownerName");
            shop.material = Material.valueOf(s.getString("material"));
            shop.quantity = s.getInt("quantity");
            shop.buyPrice = s.getDouble("buyPrice", -1);
            shop.sellPrice = s.getDouble("sellPrice", -1);
            World w = Bukkit.getWorld(s.getString("world"));
            if (w == null) continue; // world not loaded yet - skip, don't crash
            shop.chestLocation = new Location(w, s.getInt("x"), s.getInt("y"), s.getInt("z"));
            shops.put(k, shop);
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (var entry : shops.entrySet()) {
            Shop s = entry.getValue();
            String base = "shops." + entry.getKey();
            cfg.set(base + ".owner", s.owner.toString());
            cfg.set(base + ".ownerName", s.ownerName);
            cfg.set(base + ".material", s.material.name());
            cfg.set(base + ".quantity", s.quantity);
            cfg.set(base + ".buyPrice", s.buyPrice);
            cfg.set(base + ".sellPrice", s.sellPrice);
            cfg.set(base + ".world", s.chestLocation.getWorld().getName());
            cfg.set(base + ".x", s.chestLocation.getBlockX());
            cfg.set(base + ".y", s.chestLocation.getBlockY());
            cfg.set(base + ".z", s.chestLocation.getBlockZ());
        }
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save shops.yml", e);
        }
    }
}
