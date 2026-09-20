package com.ghanarealms.guard;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.SimpleDateFormat;
import java.util.Date;

public class GhanaRealmsGuard extends JavaPlugin {

    private FlagStore flagStore;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        flagStore = new FlagStore(this);
        Bukkit.getPluginManager().registerEvents(new GuardListener(this, flagStore), this);
        getCommand("guardadmin").setExecutor(this::onAdmin);

        getLogger().warning("GhanaRealmsGuard is a BASIC movement-sanity check, NOT anticheat-grade.");
        getLogger().warning("No movement prediction, no packet analysis - see plugin.yml description.");
        getLogger().warning("Use Grim or Vulcan for real protection once you can get a build of one.");
        getLogger().info("GhanaRealmsGuard enabled.");
    }

    private boolean onAdmin(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§7Usage: /guardadmin <flags|clear> <player>");
            return true;
        }
        var target = Bukkit.getOfflinePlayer(args[1]);
        if (args[0].equalsIgnoreCase("flags")) {
            var list = flagStore.get(target.getUniqueId());
            if (list.isEmpty()) {
                sender.sendMessage(color(getConfig().getString("MESSAGES.ADMIN-NO-FLAGS", "").replace("{player}", args[1])));
                return true;
            }
            sender.sendMessage(color(getConfig().getString("MESSAGES.ADMIN-FLAGS-HEADER", "").replace("{player}", args[1])));
            SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");
            for (var flag : list) {
                sender.sendMessage("§7[" + fmt.format(new Date(flag.timestamp())) + "] §f" + flag.reason());
            }
        } else if (args[0].equalsIgnoreCase("clear")) {
            flagStore.clear(target.getUniqueId());
            sender.sendMessage(color(getConfig().getString("MESSAGES.ADMIN-CLEARED", "").replace("{player}", args[1])));
        }
        return true;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
