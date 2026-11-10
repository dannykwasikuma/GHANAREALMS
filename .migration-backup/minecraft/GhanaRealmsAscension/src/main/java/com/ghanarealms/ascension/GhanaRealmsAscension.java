package com.ghanarealms.ascension;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

public class GhanaRealmsAscension extends JavaPlugin implements Listener {

    private ProgressionStore store;
    private ProgressionManager progression;
    private GateManager gates;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new ProgressionStore(this);
        progression = new ProgressionManager(this, store);
        gates = new GateManager(this, progression);

        Bukkit.getPluginManager().registerEvents(this, this);

        getCommand("rank").setExecutor(this::onRank);
        getCommand("statpoint").setExecutor(this::onStatPoint);
        getCommand("gate").setExecutor(this::onGate);

        getLogger().info("GhanaRealmsAscension enabled.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        progression.applyAttributes(player);

        var data = store.get(player.getUniqueId());
        if (!data.awakened) {
            data.awakened = true;
            store.save(player.getUniqueId());
            // Delay slightly so it doesn't collide with the server's own join
            // message / other plugins' welcome sequences firing the same tick.
            Bukkit.getScheduler().runTaskLater(this, () -> progression.playAwakening(player), 40L);
        }
    }

    @EventHandler
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        if (event.getEntity().hasMetadata("ghanarealms-gate")) return; // gate kills are XP'd on gate clear, not per-mob, to avoid double reward
        long xp = event.getEntity() instanceof Player
                ? getConfig().getLong("XP.PER-PLAYER-KILL", 40)
                : (event.getEntity() instanceof Monster ? getConfig().getLong("XP.PER-MOB-KILL", 15) : 0);
        if (xp > 0) progression.addXp(killer, xp);
    }

    private boolean onRank(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        Player target;
        if (args.length > 0) {
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found.");
                return true;
            }
        } else if (sender instanceof Player p) {
            target = p;
        } else {
            sender.sendMessage("Console must specify a player.");
            return true;
        }

        var data = store.get(target.getUniqueId());
        long nextLevelXp = progression.xpForLevel(data.level);
        for (String line : getConfig().getStringList("MESSAGES.RANK-VIEW")) {
            sender.sendMessage(color(line
                    .replace("{player}", target.getName())
                    .replace("{level}", String.valueOf(data.level))
                    .replace("{title}", color(progression.titleFor(data.level)))
                    .replace("{xp}", String.valueOf(data.xp))
                    .replace("{nextLevelXp}", String.valueOf(nextLevelXp))
                    .replace("{points}", String.valueOf(data.unspentPoints))
                    .replace("{str}", String.valueOf(data.strength))
                    .replace("{vit}", String.valueOf(data.vitality))
                    .replace("{agi}", String.valueOf(data.agility))));
        }
        return true;
    }

    private boolean onStatPoint(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length < 2) {
            player.sendMessage("§7Usage: /statpoint <strength|vitality|agility> <amount>");
            return true;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cInvalid amount.");
            return true;
        }
        boolean ok = progression.spendPoint(player, args[0], amount);
        player.sendMessage(ok ? "§aStat points applied." : "§cCould not spend points (not enough points, or stat cap reached).");
        return true;
    }

    private boolean onGate(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage("§7Usage: /gate <open> <bronze-gate|silver-gate|gold-gate>  or  /gate leave");
            return true;
        }
        if (args[0].equalsIgnoreCase("leave")) {
            gates.leave(player);
            return true;
        }
        if (args[0].equalsIgnoreCase("open")) {
            if (args.length < 2) {
                player.sendMessage("§7Usage: /gate open <bronze-gate|silver-gate|gold-gate>");
                return true;
            }
            gates.enter(player, args[1].toUpperCase());
            return true;
        }
        return true;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
