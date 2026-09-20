package com.ghanarealms.quests;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Stores per-player progress as data/<uuid>.yml. Simple file-per-player YAML
 * rather than a database - quest progress is small, low write-frequency data
 * and doesn't need SQL; this keeps the plugin dependency-free.
 */
public class ProgressStore {

    private final JavaPlugin plugin;
    private final File dataDir;
    private final Map<UUID, YamlConfiguration> cache = new HashMap<>();

    public ProgressStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.dataDir = new File(plugin.getDataFolder(), "playerdata");
        dataDir.mkdirs();
    }

    private YamlConfiguration fileFor(UUID uuid) {
        return cache.computeIfAbsent(uuid, id -> {
            File f = new File(dataDir, id + ".yml");
            return YamlConfiguration.loadConfiguration(f);
        });
    }

    private File pathFor(UUID uuid) {
        return new File(dataDir, uuid + ".yml");
    }

    public int getProgress(UUID uuid, QuestDefinition.Cycle cycle, String questId, String currentCycleKey) {
        YamlConfiguration cfg = fileFor(uuid);
        String base = cycle.name() + "." + questId;
        String storedCycleKey = cfg.getString(base + ".cycleKey", "");
        if (!storedCycleKey.equals(currentCycleKey)) {
            return 0; // stale progress from a previous day/week - treat as reset
        }
        return cfg.getInt(base + ".progress", 0);
    }

    public boolean isCompleted(UUID uuid, QuestDefinition.Cycle cycle, String questId, String currentCycleKey) {
        YamlConfiguration cfg = fileFor(uuid);
        String base = cycle.name() + "." + questId;
        String storedCycleKey = cfg.getString(base + ".cycleKey", "");
        return storedCycleKey.equals(currentCycleKey) && cfg.getBoolean(base + ".completed", false);
    }

    public void setProgress(UUID uuid, QuestDefinition.Cycle cycle, String questId, String currentCycleKey, int progress, boolean completed) {
        YamlConfiguration cfg = fileFor(uuid);
        String base = cycle.name() + "." + questId;
        cfg.set(base + ".cycleKey", currentCycleKey);
        cfg.set(base + ".progress", progress);
        cfg.set(base + ".completed", completed);
        save(uuid, cfg);
    }

    private void save(UUID uuid, YamlConfiguration cfg) {
        try {
            cfg.save(pathFor(uuid));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save quest progress for " + uuid, e);
        }
    }
}
