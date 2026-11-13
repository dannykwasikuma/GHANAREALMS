package com.ghanarealms.shops;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Server ("walk-in") shops: physical chests in the world that open a buy/sell menu instead of an
 * inventory. Stock is unlimited and paid for with the Vault economy. Which chests are shops is saved
 * in server-shop-chests.yml; what each shop sells comes from server-shops.yml.
 */
public class ServerShops {

    public static final String HUB = "hub";

    public static class Entry {
        public final Material material;
        public final double buy;   // price to buy ONE from the shop (-1 = not sold)
        public final double sell;  // price the shop pays for ONE (-1 = not bought)

        public Entry(Material material, double buy, double sell) {
            this.material = material;
            this.buy = buy;
            this.sell = sell;
        }
    }

    public static class Category {
        public final String id;
        public final String title;
        public final Material icon;
        public final List<Entry> entries;

        public Category(String id, String title, Material icon, List<Entry> entries) {
            this.id = id;
            this.title = title;
            this.icon = icon;
            this.entries = entries;
        }
    }

    private final JavaPlugin plugin;
    private final File chestsFile;
    private final Map<String, Category> categories = new LinkedHashMap<>();
    private final Map<String, String> chests = new HashMap<>(); // "world,x,y,z" -> category id

    public ServerShops(JavaPlugin plugin) {
        this.plugin = plugin;
        this.chestsFile = new File(plugin.getDataFolder(), "server-shop-chests.yml");
        reload();
    }

    // ---------------------------------------------------------------- categories

    public void reload() {
        File file = new File(plugin.getDataFolder(), "server-shops.yml");
        if (!file.exists()) plugin.saveResource("server-shops.yml", false);

        categories.clear();
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("CATEGORIES");
        if (root != null) {
            for (String id : root.getKeys(false)) {
                String key = id.toLowerCase(Locale.ROOT);
                if (key.equals(HUB)) {
                    plugin.getLogger().warning("server-shops.yml: category id 'hub' is reserved - skipped.");
                    continue;
                }
                ConfigurationSection c = root.getConfigurationSection(id);
                if (c == null) continue;
                Material icon = Material.matchMaterial(c.getString("ICON", "CHEST"));
                if (icon == null || !icon.isItem()) icon = Material.CHEST;

                List<Entry> entries = new ArrayList<>();
                for (Map<?, ?> m : c.getMapList("ITEMS")) {
                    Material mat = Material.matchMaterial(String.valueOf(m.get("material")));
                    if (mat == null || !mat.isItem() || mat == Material.AIR) {
                        plugin.getLogger().warning("server-shops.yml: unknown item '" + m.get("material") + "' in " + id + " - skipped.");
                        continue;
                    }
                    double buy = number(m.get("buy"));
                    double sell = number(m.get("sell"));
                    if (buy < 0 && sell < 0) continue; // neither bought nor sold - nothing to show
                    entries.add(new Entry(mat, buy, sell));
                }
                categories.put(key, new Category(key, c.getString("TITLE", "&6" + id), icon, entries));
            }
        }
        plugin.getLogger().info("Loaded " + categories.size() + " server shop categor" + (categories.size() == 1 ? "y" : "ies") + ".");
        loadChests();
    }

    private static double number(Object o) {
        return o instanceof Number n ? n.doubleValue() : -1;
    }

    public Category category(String id) {
        return id == null ? null : categories.get(id.toLowerCase(Locale.ROOT));
    }

    public Collection<Category> categories() {
        return categories.values();
    }

    public boolean exists(String id) {
        return id != null && (id.equalsIgnoreCase(HUB) || categories.containsKey(id.toLowerCase(Locale.ROOT)));
    }

    // ---------------------------------------------------------------- chests

    private static String key(Location loc) {
        return loc.getWorld().getName() + "," + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

    /** All block locations that make up this container (both halves of a double chest). */
    private static List<Location> halves(Block block) {
        List<Location> out = new ArrayList<>();
        if (block.getState() instanceof Chest chest) {
            InventoryHolder holder = chest.getInventory().getHolder();
            if (holder instanceof DoubleChest dc) {
                if (dc.getLeftSide() instanceof Chest l) out.add(l.getLocation());
                if (dc.getRightSide() instanceof Chest r) out.add(r.getLocation());
            }
        }
        if (out.isEmpty()) out.add(block.getLocation());
        return out;
    }

    public String categoryAt(Block block) {
        return chests.get(key(block.getLocation()));
    }

    public void bind(Block block, String categoryId) {
        for (Location loc : halves(block)) chests.put(key(loc), categoryId.toLowerCase(Locale.ROOT));
        saveChests();
    }

    public void unbind(Block block) {
        for (Location loc : halves(block)) chests.remove(key(loc));
        chests.remove(key(block.getLocation()));
        saveChests();
    }

    public Map<String, String> chests() {
        return Collections.unmodifiableMap(chests);
    }

    private void loadChests() {
        chests.clear();
        if (!chestsFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(chestsFile);
        for (String line : cfg.getStringList("chests")) {
            String[] p = line.split("\\|");
            if (p.length != 5) continue;
            World w = Bukkit.getWorld(p[1]);
            if (w == null) continue; // world not loaded - skip rather than crash
            try {
                chests.put(p[1] + "," + Integer.parseInt(p[2]) + "," + Integer.parseInt(p[3]) + "," + Integer.parseInt(p[4]), p[0]);
            } catch (NumberFormatException ignored) {
                // corrupt line - skip
            }
        }
    }

    private void saveChests() {
        YamlConfiguration cfg = new YamlConfiguration();
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> e : chests.entrySet()) {
            String[] loc = e.getKey().split(",");
            if (loc.length != 4) continue;
            lines.add(e.getValue() + "|" + loc[0] + "|" + loc[1] + "|" + loc[2] + "|" + loc[3]);
        }
        cfg.set("chests", lines);
        try {
            cfg.save(chestsFile);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to save server-shop-chests.yml", ex);
        }
    }
}
