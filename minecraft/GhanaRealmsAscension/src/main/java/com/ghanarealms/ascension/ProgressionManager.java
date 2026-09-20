package com.ghanarealms.ascension;

import org.bukkit.ChatColor;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

public class ProgressionManager {

    private final JavaPlugin plugin;
    private final ProgressionStore store;
    private final NavigableMap<Integer, String> rankTitles = new TreeMap<>();

    private static final UUID STR_MODIFIER_ID = UUID.fromString("a1a1a1a1-0000-0000-0000-000000000001");
    private static final UUID VIT_MODIFIER_ID = UUID.fromString("a1a1a1a1-0000-0000-0000-000000000002");
    private static final UUID AGI_MODIFIER_ID = UUID.fromString("a1a1a1a1-0000-0000-0000-000000000003");

    public ProgressionManager(JavaPlugin plugin, ProgressionStore store) {
        this.plugin = plugin;
        this.store = store;
        loadRankTitles();
    }

    private void loadRankTitles() {
        var section = plugin.getConfig().getConfigurationSection("RANKS");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            rankTitles.put(Integer.parseInt(key), section.getString(key + ".TITLE", "Recruit"));
        }
    }

    public String titleFor(int level) {
        var entry = rankTitles.floorEntry(level);
        return entry != null ? entry.getValue() : "&7Recruit";
    }

    public long xpForLevel(int level) {
        double base = plugin.getConfig().getDouble("XP.BASE", 50);
        double exp = plugin.getConfig().getDouble("XP.CURVE-EXPONENT", 1.6);
        return Math.round(base * Math.pow(level, exp));
    }

    public void addXp(Player player, long amount) {
        var data = store.get(player.getUniqueId());
        data.xp += amount;

        boolean leveledUp = false;
        while (data.xp >= xpForLevel(data.level)) {
            data.xp -= xpForLevel(data.level);
            data.level++;
            int points = plugin.getConfig().getInt("XP.STAT-POINTS-PER-LEVEL", 2);
            data.unspentPoints += points;
            leveledUp = true;

            String msg = plugin.getConfig().getString("MESSAGES.LEVEL-UP", "&6Level up!")
                    .replace("{level}", String.valueOf(data.level))
                    .replace("{title}", ChatColor.stripColor(color(titleFor(data.level))))
                    .replace("{points}", String.valueOf(points));
            player.sendMessage(color(msg));

            String title = color(plugin.getConfig().getString("MESSAGES.LEVEL-UP-TITLE", "&6LEVEL UP"));
            String subtitle = color(plugin.getConfig().getString("MESSAGES.LEVEL-UP-SUBTITLE", "")
                    .replace("{level}", String.valueOf(data.level))
                    .replace("{title}", ChatColor.stripColor(color(titleFor(data.level)))));
            player.showTitle(net.kyori.adventure.title.Title.title(
                    net.kyori.adventure.text.Component.text(title),
                    net.kyori.adventure.text.Component.text(subtitle),
                    net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(2500), java.time.Duration.ofMillis(500))));
            playConfiguredSound(player, "LEVEL-UP");
            player.getWorld().spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING, player.getLocation().add(0, 1, 0), 40, 0.5, 1, 0.5, 0.05);
        }
        store.save(player.getUniqueId());
        if (leveledUp) applyAttributes(player);
    }

    /** Called once, the first time a player is ever seen (level 1, 0 xp, no
     *  points spent) - a one-off dramatic entrance rather than a level-up. */
    public void playAwakening(Player player) {
        String title = color(plugin.getConfig().getString("MESSAGES.AWAKENING-TITLE", "&eTHE CALLING"));
        String subtitle = color(plugin.getConfig().getString("MESSAGES.AWAKENING-SUBTITLE", "")
                .replace("{player}", player.getName()));
        player.showTitle(net.kyori.adventure.title.Title.title(
                net.kyori.adventure.text.Component.text(title),
                net.kyori.adventure.text.Component.text(subtitle),
                net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(500), java.time.Duration.ofMillis(3000), java.time.Duration.ofMillis(1000))));
        playConfiguredSound(player, "AWAKENING");
        player.getWorld().spawnParticle(org.bukkit.Particle.SOUL, player.getLocation().add(0, 1, 0), 60, 0.6, 1.2, 0.6, 0.02);
    }

    public void playConfiguredSound(Player player, String key) {
        String soundName = plugin.getConfig().getString("SOUNDS." + key);
        if (soundName == null) return;
        try {
            org.bukkit.Sound sound = org.bukkit.Sound.valueOf(soundName);
            player.playSound(player.getLocation(), sound, 1.0f, 1.0f);
        } catch (IllegalArgumentException ignored) {
            // unknown sound name in config - fail silently rather than error-spam the console
        }
    }

    public boolean spendPoint(Player player, String stat, int amount) {
        var data = store.get(player.getUniqueId());
        if (amount <= 0 || data.unspentPoints < amount) return false;

        int max = plugin.getConfig().getInt("STATS." + stat.toUpperCase() + ".MAX-POINTS", 50);
        int current = switch (stat.toLowerCase()) {
            case "strength" -> data.strength;
            case "vitality" -> data.vitality;
            case "agility" -> data.agility;
            default -> -1;
        };
        if (current < 0) return false;
        if (current + amount > max) return false;

        switch (stat.toLowerCase()) {
            case "strength" -> data.strength += amount;
            case "vitality" -> data.vitality += amount;
            case "agility" -> data.agility += amount;
        }
        data.unspentPoints -= amount;
        store.save(player.getUniqueId());
        applyAttributes(player);
        return true;
    }

    /** Applies STR/VIT/AGI as capped Bukkit AttributeModifiers - this keeps
     *  progression inside vanilla's normal attribute system rather than a
     *  custom damage-calculation layer, so it stays compatible with
     *  anything else touching combat (anticheat, other plugins). */
    public void applyAttributes(Player player) {
        var data = store.get(player.getUniqueId());

        setModifier(player, Attribute.GENERIC_ATTACK_DAMAGE, STR_MODIFIER_ID, "gr-strength",
                data.strength * plugin.getConfig().getDouble("STATS.STRENGTH.PER-POINT-DAMAGE", 0.15));
        setModifier(player, Attribute.GENERIC_MAX_HEALTH, VIT_MODIFIER_ID, "gr-vitality",
                data.vitality * plugin.getConfig().getDouble("STATS.VITALITY.PER-POINT-HEALTH", 0.5));
        setModifier(player, Attribute.GENERIC_MOVEMENT_SPEED, AGI_MODIFIER_ID, "gr-agility",
                data.agility * plugin.getConfig().getDouble("STATS.AGILITY.PER-POINT-SPEED", 0.004));
    }

    private void setModifier(Player player, Attribute attribute, UUID modifierId, String name, double value) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) return;
        instance.getModifiers().stream()
                .filter(m -> m.getUniqueId().equals(modifierId))
                .findFirst()
                .ifPresent(instance::removeModifier);
        if (value != 0) {
            instance.addModifier(new AttributeModifier(modifierId, name, value, AttributeModifier.Operation.ADD_NUMBER));
        }
    }

    public ProgressionStore getStore() {
        return store;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
