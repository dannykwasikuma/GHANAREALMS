package com.ghanarealms.guard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Deliberately basic. See plugin.yml / config.yml for what this is not.
 * No movement prediction, no packet inspection, no client-side data at
 * all - purely "does this look physically implausible given generous
 * headroom." Anyone running an actual cheat client with even mild legit-
 * movement simulation will not be caught here.
 */
public class GuardListener implements Listener {

    private record MoveState(Location lastLoc, long lastTimeMs, int airborneTicks) {}

    private final JavaPlugin plugin;
    private final FlagStore flagStore;
    private final Map<UUID, MoveState> moveStates = new HashMap<>();

    public GuardListener(JavaPlugin plugin, FlagStore flagStore) {
        this.plugin = plugin;
        this.flagStore = flagStore;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("ghanarealmsguard.bypass")) return;
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE
                || player.getGameMode() == org.bukkit.GameMode.SPECTATOR
                || player.isFlying() || player.getAllowFlight()
                || player.isInsideVehicle() || player.isGliding()) {
            moveStates.remove(player.getUniqueId());
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        MoveState prev = moveStates.get(player.getUniqueId());
        long now = System.currentTimeMillis();

        if (prev != null) {
            checkSpeed(player, prev, to, now);
        }

        int airborne = computeAirborneTicks(player, prev, to);
        checkFlight(player, airborne);

        moveStates.put(player.getUniqueId(), new MoveState(to, now, airborne));
    }

    private void checkSpeed(Player player, MoveState prev, Location to, long now) {
        if (!plugin.getConfig().getBoolean("SPEED.ENABLED", true)) return;
        long deltaMs = now - prev.lastTimeMs();
        if (deltaMs <= 0 || deltaMs > 1000) return; // skip on lag spikes / teleports rather than false-flag them

        double dx = to.getX() - prev.lastLoc().getX();
        double dz = to.getZ() - prev.lastLoc().getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);
        double blocksPerSecond = horizontalDist / (deltaMs / 1000.0);

        double max = plugin.getConfig().getDouble("SPEED.MAX-BLOCKS-PER-SECOND", 12.0);
        // widen the allowance further if the player has an active Speed effect,
        // rather than guessing at an exact multiplier
        if (player.hasPotionEffect(PotionEffectType.SPEED)) max *= 1.6;

        if (blocksPerSecond > max) {
            flagStore.flag(player, "speed (" + String.format("%.1f", blocksPerSecond) + " blocks/s)");
        }
    }

    private int computeAirborneTicks(Player player, MoveState prev, Location to) {
        boolean onGroundNow = player.isOnGround();
        boolean fallingOrSlowed = player.getVelocity().getY() < -0.05
                || player.hasPotionEffect(PotionEffectType.LEVITATION)
                || player.hasPotionEffect(PotionEffectType.SLOW_FALLING);
        if (onGroundNow || fallingOrSlowed) return 0;
        int prevTicks = prev == null ? 0 : prevAirborneTicksOf(player);
        return prevTicks + 1;
    }

    // Small helper only exists because MoveState is a record without a settable
    // field - re-derive from the map instead of threading extra state around.
    private int prevAirborneTicksOf(Player player) {
        MoveState s = moveStates.get(player.getUniqueId());
        return s == null ? 0 : s.airborneTicks();
    }

    private void checkFlight(Player player, int airborneTicks) {
        if (!plugin.getConfig().getBoolean("FLIGHT.ENABLED", true)) return;
        int max = plugin.getConfig().getInt("FLIGHT.MAX-AIRBORNE-TICKS", 60);
        if (airborneTicks == max) { // flag once per episode, not every tick past the threshold
            flagStore.flag(player, "sustained flight (" + (airborneTicks / 20) + "s airborne, no fall/levitation)");
        }
    }

    @EventHandler
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!plugin.getConfig().getBoolean("REACH.ENABLED", true)) return;
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (attacker.hasPermission("ghanarealmsguard.bypass")) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;

        double distance = attacker.getEyeLocation().distance(event.getEntity().getLocation());
        double max = plugin.getConfig().getDouble("REACH.MAX-ATTACK-DISTANCE", 6.0);
        if (distance > max) {
            flagStore.flag(attacker, "reach (" + String.format("%.1f", distance) + " blocks)");
        }
    }
}
