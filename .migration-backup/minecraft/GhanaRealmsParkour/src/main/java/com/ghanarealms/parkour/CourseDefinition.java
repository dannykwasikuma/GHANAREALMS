package com.ghanarealms.parkour;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CourseDefinition {
    public final String id;
    public String displayName;
    public String world;
    public double startX, startY, startZ;
    public double finishX, finishY, finishZ, finishRadius = 2;
    public final List<double[]> checkpoints = new ArrayList<>();

    public CourseDefinition(String id) {
        this.id = id;
    }

    public static Map<String, CourseDefinition> loadAll(ConfigurationSection root) {
        Map<String, CourseDefinition> out = new java.util.HashMap<>();
        if (root == null) return out;
        for (String key : root.getKeys(false)) {
            var section = root.getConfigurationSection(key);
            CourseDefinition c = new CourseDefinition(key);
            c.displayName = section.getString("DISPLAY-NAME", key);
            c.world = section.getString("WORLD", "world");
            c.startX = section.getDouble("START.X");
            c.startY = section.getDouble("START.Y");
            c.startZ = section.getDouble("START.Z");
            c.finishX = section.getDouble("FINISH.X");
            c.finishY = section.getDouble("FINISH.Y");
            c.finishZ = section.getDouble("FINISH.Z");
            c.finishRadius = section.getDouble("FINISH.RADIUS", 2);
            var cpList = section.getMapList("CHECKPOINTS");
            for (var cp : cpList) {
                double x = ((Number) cp.get("X")).doubleValue();
                double y = ((Number) cp.get("Y")).doubleValue();
                double z = ((Number) cp.get("Z")).doubleValue();
                c.checkpoints.add(new double[]{x, y, z});
            }
            out.put(key, c);
        }
        return out;
    }

    public Location startLocation(World w) {
        return new Location(w, startX, startY, startZ);
    }

    public boolean atCheckpoint(Location loc, int index, double radius) {
        if (index >= checkpoints.size()) return false;
        double[] cp = checkpoints.get(index);
        return dist(loc, cp[0], cp[1], cp[2]) <= radius;
    }

    public boolean atFinish(Location loc) {
        return dist(loc, finishX, finishY, finishZ) <= finishRadius;
    }

    private double dist(Location loc, double x, double y, double z) {
        double dx = loc.getX() - x, dy = loc.getY() - y, dz = loc.getZ() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
