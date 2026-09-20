package com.ghanarealms.casino;

import com.ghanarealms.casino.oware.OwareManager;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class GhanaRealmsCasino extends JavaPlugin {

    private Economy economy;
    private OwareManager owareManager;
    private CediTossManager cediTossManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        setupVault();

        owareManager = new OwareManager(this, economy);
        cediTossManager = new CediTossManager(this, economy);

        getCommand("oware").setExecutor(this::onOware);
        getCommand("cedistoss").setExecutor(this::onCediToss);

        getLogger().info("GhanaRealmsCasino enabled (Oware + Cedi Toss).");
    }

    private void setupVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found - wagering is disabled until it's installed.");
            return;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }

    private boolean onOware(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage("§7Usage: /oware <challenge|accept|decline|forfeit> [player] [amount]");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "challenge" -> {
                if (args.length < 3) {
                    player.sendMessage("§cUsage: /oware challenge <player> <amount>");
                    return true;
                }
                Player target = Bukkit.getPlayer(args[1]);
                if (target == null || target.equals(player)) {
                    player.sendMessage("§cPlayer not found.");
                    return true;
                }
                double amount;
                try {
                    amount = Double.parseDouble(args[2]);
                } catch (NumberFormatException e) {
                    player.sendMessage("§cInvalid amount.");
                    return true;
                }
                owareManager.challenge(player, target, amount);
            }
            case "accept" -> {
                if (args.length < 2) {
                    player.sendMessage("§cUsage: /oware accept <challenger>");
                    return true;
                }
                Player challenger = Bukkit.getPlayer(args[1]);
                if (challenger == null) {
                    player.sendMessage("§cPlayer not found.");
                    return true;
                }
                owareManager.accept(player, challenger);
            }
            case "forfeit" -> owareManager.forfeit(player);
            default -> player.sendMessage("§7Usage: /oware <challenge|accept|decline|forfeit> [player] [amount]");
        }
        return true;
    }

    private boolean onCediToss(org.bukkit.command.CommandSender sender, org.bukkit.command.Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }
        if (args.length == 0) {
            player.sendMessage("§7Usage: /cedistoss <challenge|accept> <player> [amount]");
            return true;
        }
        if (args[0].equalsIgnoreCase("accept")) {
            if (args.length < 2) {
                player.sendMessage("§cUsage: /cedistoss accept <challenger>");
                return true;
            }
            Player challenger = Bukkit.getPlayer(args[1]);
            if (challenger == null) {
                player.sendMessage("§cPlayer not found.");
                return true;
            }
            cediTossManager.accept(player, challenger);
            return true;
        }
        // otherwise: /cedistoss <player> <amount>  (challenge)
        if (args.length < 2) {
            player.sendMessage("§7Usage: /cedistoss <player> <amount>  (or /cedistoss accept <player>)");
            return true;
        }
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || target.equals(player)) {
            player.sendMessage("§cPlayer not found.");
            return true;
        }
        double amount;
        try {
            amount = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cInvalid amount.");
            return true;
        }
        cediTossManager.challenge(player, target, amount);
        return true;
    }
}
