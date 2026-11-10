package com.ghanarealms.quests;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Map;

public class QuestDefinition {

    public enum Type { BREAK_BLOCK, FISH, KILL_ENTITY, VISIT_REGION, WALK_DISTANCE }
    public enum Cycle { DAILY, WEEKLY }

    public final String id;
    public final Cycle cycle;
    public final String displayName;
    public final String description;
    public final Type type;
    public final int amount;
    public final double rewardMoney;

    // type-specific fields
    public String material;
    public String entityType;
    public String world;
    public double x, y, z, radius;

    public QuestDefinition(String id, Cycle cycle, ConfigurationSection section) {
        this.id = id;
        this.cycle = cycle;
        this.displayName = section.getString("DISPLAY-NAME", id);
        this.description = section.getString("DESCRIPTION", "");
        this.type = Type.valueOf(section.getString("TYPE", "BREAK_BLOCK"));
        this.amount = section.getInt("AMOUNT", 1);
        this.rewardMoney = section.getDouble("REWARD-MONEY", 0);

        this.material = section.getString("MATERIAL");
        this.entityType = section.getString("ENTITY-TYPE");
        this.world = section.getString("WORLD");
        this.x = section.getDouble("X");
        this.y = section.getDouble("Y");
        this.z = section.getDouble("Z");
        this.radius = section.getDouble("RADIUS", 10);
    }

    public boolean matchesLocation(Location loc) {
        if (world == null) return false;
        World w = loc.getWorld();
        if (w == null || !w.getName().equals(world)) return false;
        double dx = loc.getX() - x, dy = loc.getY() - y, dz = loc.getZ() - z;
        return (dx * dx + dy * dy + dz * dz) <= (radius * radius);
    }

    public static Map<String, QuestDefinition> loadAll(ConfigurationSection root, Cycle cycle) {
        Map<String, QuestDefinition> out = new HashMap<>();
        if (root == null) return out;
        for (String key : root.getKeys(false)) {
            out.put(key, new QuestDefinition(key, cycle, root.getConfigurationSection(key)));
        }
        return out;
    }
}
