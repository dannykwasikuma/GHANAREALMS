package com.ghanarealms.paystack;

import com.ghanarealms.paystack.storage.PurchaseStore;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class PaystackAdminCommand implements CommandExecutor {

    private final GhanaRealmsPaystack plugin;

    public PaystackAdminCommand(GhanaRealmsPaystack plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(color("&7Usage: /paystack <verify|reload|history|packages>"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadConfig();
                sender.sendMessage(color("&aConfig reloaded."));
            }
            case "verify" -> {
                if (args.length < 2) {
                    sender.sendMessage(color("&cUsage: /paystack verify <reference>"));
                    return true;
                }
                String reference = args[1];
                PurchaseStore.Purchase p = plugin.getStore().find(reference);
                if (p == null) {
                    sender.sendMessage(color("&cNo purchase record found for that reference."));
                    return true;
                }
                if (p.status() == PurchaseStore.Status.SUCCESS) {
                    sender.sendMessage(color("&eAlready marked SUCCESS - not re-delivering (duplicate protection)."));
                    return true;
                }
                PaystackClient.VerifyResult verify = plugin.getClient().verifyTransaction(reference);
                if (!verify.ok() || !"success".equals(verify.status())) {
                    sender.sendMessage(color("&cPaystack says this transaction is not successful: " + verify.message()));
                    plugin.getStore().markFailed(reference);
                    return true;
                }
                boolean first = plugin.getStore().markSuccessOnce(reference);
                if (!first) {
                    sender.sendMessage(color("&eAlready processed by another path."));
                    return true;
                }
                plugin.deliverPurchase(p);
                sender.sendMessage(color("&aVerified and delivered."));
            }
            case "history" -> {
                String targetName = args.length >= 2 ? args[1] : sender.getName();
                var history = plugin.getStore().history(targetName, 10);
                if (history.isEmpty()) {
                    sender.sendMessage(color("&7No purchase history for " + targetName + "."));
                    return true;
                }
                sender.sendMessage(color("&6Purchase history for " + targetName + ":"));
                for (var p : history) {
                    sender.sendMessage(color("&7- " + p.packageId() + " &7GH₵" + p.amountGhs() + " &7[" + p.status() + "] &8" + p.reference()));
                }
            }
            case "packages" -> {
                var packages = plugin.getConfig().getConfigurationSection("PACKAGES");
                if (packages == null) {
                    sender.sendMessage(color("&cNo packages configured."));
                    return true;
                }
                for (String key : packages.getKeys(false)) {
                    var pkg = packages.getConfigurationSection(key);
                    sender.sendMessage(color("&7- " + key + " &7GH₵" + pkg.getDouble("PRICE-GHS", 0)
                            + " give=" + pkg.getDouble("GIVE-MONEY", 0)
                            + " commands=" + pkg.getStringList("COMMANDS").size()));
                }
            }
            default -> sender.sendMessage(color("&7Usage: /paystack <verify|reload|history|packages>"));
        }
        return true;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
