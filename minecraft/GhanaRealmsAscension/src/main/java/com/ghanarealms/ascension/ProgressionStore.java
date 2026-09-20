package com.ghanarealms.ascension;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public class ProgressionStore {

    public static class PlayerData {
        public int level = 1;
        public long xp = 0;
        public int unspentPoints = 0;
        public int strength = 0;
        public int vitality = 0;
        public int agility = 0;
        public boolean awakened = false;
    }

    private final JavaPlugin plugin;
    private final File dataDir;
    private final Map<UUID, PlayerData> cache = new HashMap<>();

    public ProgressionStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataDir = new File(plugin.getDataFolder(), "playerdata");
        dataDir.mkdirs();
    }

    public PlayerData get(UUID uuid) {
        return cache.computeIfAbsent(uuid, this::load);
    }

    private PlayerData load(UUID uuid) {
        File f = new File(dataDir, uuid + ".yml");
        PlayerData data = new PlayerData();
        if (f.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            data.level = cfg.getInt("level", 1);
            data.xp = cfg.getLong("xp", 0);
            data.unspentPoints = cfg.getInt("unspentPoints", 0);
            data.strength = cfg.getInt("strength", 0);
            data.vitality = cfg.getInt("vitality", 0);
            data.agility = cfg.getInt("agility", 0);
            data.awakened = cfg.getBoolean("awakened", false);
        }
        return data;
    }

    public void save(UUID uuid) {
        PlayerData data = cache.get(uuid);
        if (data == null) return;
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("level", data.level);
        cfg.set("xp", data.xp);
        cfg.set("unspentPoints", data.unspentPoints);
        cfg.set("strength", data.strength);
        cfg.set("vitality", data.vitality);
        cfg.set("agility", data.agility);
        cfg.set("awakened", data.awakened);
        try {
            cfg.save(new File(dataDir, uuid + ".yml"));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save progression for " + uuid, e);
        }
    }
}
