package com.ghanarealms.guard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

/**
 * In-memory only (resets on restart) - flags are a lightweight staff signal,
 * not a punishment record, so this doesn't need database persistence the
 * way an actual anticheat's evidence log would.
 */
public class FlagStore {

    public record Flag(String reason, long timestamp) {}

    private final JavaPlugin plugin;
    private final Map<UUID, List<Flag>> flags = new HashMap<>();

    public FlagStore(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void flag(Player player, String reason) {
        List<Flag> list = flags.computeIfAbsent(player.getUniqueId(), k -> new ArrayList<>());
        list.add(new Flag(reason, System.currentTimeMillis()));

        String alert = plugin.getConfig().getString("MESSAGES.STAFF-ALERT", "")
                .replace("{player}", player.getName())
                .replace("{reason}", reason)
                .replace("{count}", String.valueOf(list.size()));
        String colored = ChatColor.translateAlternateColorCodes('&', alert);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("ghanarealmsguard.admin")) staff.sendMessage(colored);
        }
        Bukkit.getConsoleSender().sendMessage(colored);

        boolean alertOnly = plugin.getConfig().getBoolean("GENERAL.ALERT-ONLY", true);
        int kickAt = plugin.getConfig().getInt("GENERAL.AUTO-KICK-AFTER-FLAGS", 10);
        if (!alertOnly && list.size() >= kickAt) {
            player.kick(ChatColor.translateAlternateColorCodes('&', "&cRemoved for suspicious activity - contact staff if this was in error."));
            list.clear();
        }
    }

    public List<Flag> get(UUID uuid) {
        return flags.getOrDefault(uuid, List.of());
    }

    public void clear(UUID uuid) {
        flags.remove(uuid);
    }
}
