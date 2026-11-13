package com.ghanarealms.parkour;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

public class LeaderboardStore {

    private final JavaPlugin plugin;
    private final File file;
    // course -> (playerUuid -> bestTimeMillis)
    private final Map<String, Map<UUID, Long>> bestTimes = new HashMap<>();
    private final Set<String> finishedOnce = new HashSet<>(); // "course:uuid" keys, for the first-clear bonus

    public LeaderboardStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "leaderboards.yml");
        load();
    }

    private void load() {
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        var courses = cfg.getConfigurationSection("times");
        if (courses != null) {
            for (String course : courses.getKeys(false)) {
                Map<UUID, Long> map = new HashMap<>();
                var players = courses.getConfigurationSection(course);
                for (String uuidStr : players.getKeys(false)) {
                    map.put(UUID.fromString(uuidStr), players.getLong(uuidStr));
                }
                bestTimes.put(course, map);
            }
        }
        finishedOnce.addAll(cfg.getStringList("finishedOnce"));
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (var entry : bestTimes.entrySet()) {
            for (var pEntry : entry.getValue().entrySet()) {
                cfg.set("times." + entry.getKey() + "." + pEntry.getKey(), pEntry.getValue());
            }
        }
        cfg.set("finishedOnce", new ArrayList<>(finishedOnce));
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to save leaderboards.yml", e);
        }
    }

    /** Returns true if this is a new personal best (or first time ever). */
    public boolean recordTime(String course, UUID player, long millis) {
        Map<UUID, Long> map = bestTimes.computeIfAbsent(course, k -> new HashMap<>());
        Long current = map.get(player);
        boolean improved = current == null || millis < current;
        if (improved) {
            map.put(player, millis);
            save();
        }
        return improved;
    }

    public Long bestTime(String course, UUID player) {
        return bestTimes.getOrDefault(course, Map.of()).get(player);
    }

    public boolean isFirstFinish(String course, UUID player) {
        String key = course + ":" + player;
        if (finishedOnce.contains(key)) return false;
        finishedOnce.add(key);
        save();
        return true;
    }

    public List<Map.Entry<UUID, Long>> top(String course, int limit) {
        return bestTimes.getOrDefault(course, Map.of()).entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(limit)
                .toList();
    }
}
