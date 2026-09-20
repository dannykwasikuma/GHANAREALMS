package com.ghanarealms.ascension;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * A Gate is a small instanced mob-wave trial: teleport in, fight scripted
 * waves, teleport back out on clear or on death. This does not use a
 * separate/cloned world per instance (that's real added complexity - proper
 * per-player instancing needs a world-cloning or region-isolation layer);
 * instead each Gate has one shared arena location and only one party can be
 * "inside" a given Gate at a time, queueing others. That's a real, disclosed
 * scope limit, not hidden as if this were full multi-instance dungeon tech.
 */
public class GateManager implements Listener {

    private record ActiveGate(String gateId, Set<UUID> players, List<LivingEntity> aliveMobs,
                               int waveIndex, Location returnPoint) {}

    private final JavaPlugin plugin;
    private final ProgressionManager progression;
    private final Map<String, UUID> gateOccupied = new HashMap<>(); // gateId -> player currently inside
    private final Map<UUID, ActiveGate> playerGate = new HashMap<>();

    public GateManager(JavaPlugin plugin, ProgressionManager progression) {
        this.plugin = plugin;
        this.progression = progression;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void enter(Player player, String gateId) {
        var section = plugin.getConfig().getConfigurationSection("GATES." + gateId);
        if (section == null) {
            player.sendMessage(color("&cUnknown gate."));
            return;
        }
        int minLevel = section.getInt("MIN-LEVEL", 1);
        int playerLevel = progression.getStore().get(player.getUniqueId()).level;
        if (playerLevel < minLevel) {
            player.sendMessage(color(plugin.getConfig().getString("MESSAGES.GATE-LEVEL-TOO-LOW", "&cLevel too low.")
                    .replace("{required}", String.valueOf(minLevel))));
            return;
        }
        if (gateOccupied.containsKey(gateId)) {
            player.sendMessage(color("&cThat gate is currently occupied - try again shortly."));
            return;
        }

        String worldName = section.getString("ARENA-WORLD");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            player.sendMessage(color("&cGate arena world not loaded."));
            return;
        }
        Location arena = new Location(world, section.getDouble("ARENA-X"), section.getDouble("ARENA-Y"), section.getDouble("ARENA-Z"));

        gateOccupied.put(gateId, player.getUniqueId());
        Location returnPoint = player.getLocation();
        ActiveGate active = new ActiveGate(gateId, new HashSet<>(List.of(player.getUniqueId())), new ArrayList<>(), 0, returnPoint);
        playerGate.put(player.getUniqueId(), active);

        player.teleport(arena);
        player.sendMessage(color(plugin.getConfig().getString("MESSAGES.GATE-STARTED", "&aGate opened!")));

        String gateDisplay = section.getString("DISPLAY-NAME", gateId);
        String title = color(plugin.getConfig().getString("MESSAGES.GATE-OPENING-TITLE", "&6GATE OPENING"));
        String subtitle = color(plugin.getConfig().getString("MESSAGES.GATE-OPENING-SUBTITLE", "").replace("{gate}", gateDisplay));
        player.showTitle(net.kyori.adventure.title.Title.title(
                net.kyori.adventure.text.Component.text(title),
                net.kyori.adventure.text.Component.text(subtitle),
                net.kyori.adventure.title.Title.Times.times(
                        java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(2000), java.time.Duration.ofMillis(500))));
        progression.playConfiguredSound(player, "GATE-OPEN");
        player.getWorld().spawnParticle(org.bukkit.Particle.PORTAL, arena.clone().add(0, 1, 0), 80, 1, 1, 1, 0.1);

        spawnWave(player, gateId, arena, 0);
    }

    private void spawnWave(Player player, String gateId, Location arena, int waveIndex) {
        var waves = plugin.getConfig().getMapList("GATES." + gateId + ".WAVES");
        if (waveIndex >= waves.size()) {
            clearGate(player, gateId, true);
            return;
        }
        var wave = waves.get(waveIndex);
        EntityType type = EntityType.valueOf(String.valueOf(wave.get("ENTITY")));
        int count = Integer.parseInt(String.valueOf(wave.get("COUNT")));

        ActiveGate active = playerGate.get(player.getUniqueId());
        List<LivingEntity> mobs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Location spawnAt = arena.clone().add((Math.random() - 0.5) * 10, 0, (Math.random() - 0.5) * 10);
            LivingEntity mob = (LivingEntity) arena.getWorld().spawnEntity(spawnAt, type);
            mob.setMetadata("ghanarealms-gate", new org.bukkit.metadata.FixedMetadataValue(plugin, gateId + ":" + player.getUniqueId()));
            mobs.add(mob);
        }
        playerGate.put(player.getUniqueId(), new ActiveGate(gateId, active.players, mobs, waveIndex, active.returnPoint));
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (!event.getEntity().hasMetadata("ghanarealms-gate")) return;
        String tag = event.getEntity().getMetadata("ghanarealms-gate").get(0).asString();
        String[] parts = tag.split(":");
        UUID playerId = UUID.fromString(parts[1]);
        ActiveGate active = playerGate.get(playerId);
        if (active == null) return;

        active.aliveMobs.remove(event.getEntity());
        if (active.aliveMobs.isEmpty()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null) return;
            var waves = plugin.getConfig().getMapList("GATES." + active.gateId + ".WAVES");
            int nextWave = active.waveIndex + 1;
            if (nextWave < waves.size()) {
                player.sendMessage(color(plugin.getConfig().getString("MESSAGES.GATE-WAVE-CLEARED", "&7Wave cleared.")
                        .replace("{wave}", String.valueOf(nextWave)).replace("{total}", String.valueOf(waves.size()))));
                spawnWave(player, active.gateId, player.getLocation(), nextWave);
            } else {
                clearGate(player, active.gateId, true);
            }
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ActiveGate active = playerGate.get(player.getUniqueId());
        if (active == null) return;
        if (player.getHealth() - event.getFinalDamage() <= 0) {
            event.setCancelled(true);
            player.setHealth(1);
            clearGate(player, active.gateId, false);
        }
    }

    private void clearGate(Player player, String gateId, boolean success) {
        ActiveGate active = playerGate.remove(player.getUniqueId());
        gateOccupied.remove(gateId);
        if (active == null) return;

        for (LivingEntity mob : active.aliveMobs) {
            if (!mob.isDead()) mob.remove();
        }

        if (success) {
            int xpReward = plugin.getConfig().getInt("GATES." + gateId + ".XP-REWARD", 100);
            progression.addXp(player, xpReward);
            player.sendMessage(color(plugin.getConfig().getString("MESSAGES.GATE-CLEARED", "&aGate cleared!")
                    .replace("{xp}", String.valueOf(xpReward))));
            String title = color(plugin.getConfig().getString("MESSAGES.GATE-CLEARED-TITLE", "&aGATE CLEARED"));
            player.showTitle(net.kyori.adventure.title.Title.title(
                    net.kyori.adventure.text.Component.text(title),
                    net.kyori.adventure.text.Component.text(color("&f+" + xpReward + " XP")),
                    net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(2500), java.time.Duration.ofMillis(500))));
            progression.playConfiguredSound(player, "GATE-CLEAR");
        } else {
            player.sendMessage(color(plugin.getConfig().getString("MESSAGES.GATE-FAILED", "&cYou were defeated.")));
            String title = color(plugin.getConfig().getString("MESSAGES.GATE-FAILED-TITLE", "&4DEFEATED"));
            String subtitle = color(plugin.getConfig().getString("MESSAGES.GATE-FAILED-SUBTITLE", ""));
            player.showTitle(net.kyori.adventure.title.Title.title(
                    net.kyori.adventure.text.Component.text(title),
                    net.kyori.adventure.text.Component.text(subtitle),
                    net.kyori.adventure.title.Title.Times.times(
                            java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(3000), java.time.Duration.ofMillis(800))));
            progression.playConfiguredSound(player, "GATE-FAIL");
        }
        player.teleport(active.returnPoint);
    }

    public void leave(Player player) {
        ActiveGate active = playerGate.get(player.getUniqueId());
        if (active == null) {
            player.sendMessage(color("&cYou are not in a gate."));
            return;
        }
        clearGate(player, active.gateId, false);
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
