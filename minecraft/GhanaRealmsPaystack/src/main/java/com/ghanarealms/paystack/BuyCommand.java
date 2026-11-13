package com.ghanarealms.paystack;

import com.ghanarealms.paystack.storage.PurchaseStore;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public class BuyCommand implements CommandExecutor {

    private final GhanaRealmsPaystack plugin;

    public BuyCommand(GhanaRealmsPaystack plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only.");
            return true;
        }

        ConfigurationSection packages = plugin.getConfig().getConfigurationSection("PACKAGES");
        if (packages == null) {
            player.sendMessage(color("&cNo packages configured."));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(color("&6&lGhanaRealms Store"));
            for (String key : packages.getKeys(false)) {
                ConfigurationSection p = packages.getConfigurationSection(key);
                String display = color(p.getString("DISPLAY-NAME", key));
                double price = p.getDouble("PRICE-GHS", 0);
                player.sendMessage(display + color(" &7- &fGH₵" + price + " &7- /buy " + key.toLowerCase()));
            }
            return true;
        }

        String packageId = args[0].toUpperCase();
        Set<String> keys = packages.getKeys(false);
        if (!keys.contains(packageId)) {
            player.sendMessage(color(plugin.getConfig().getString("MESSAGES.UNKNOWN-PACKAGE", "&cUnknown package.")));
            return true;
        }

        ConfigurationSection pkg = packages.getConfigurationSection(packageId);
        double priceGhs = pkg.getDouble("PRICE-GHS", 0);
        String reference = "GR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);

        boolean created = plugin.getStore().createPending(
                reference, player.getName(), player.getUniqueId().toString(), packageId, priceGhs
        );
        if (!created) {
            player.sendMessage(color("&cCould not start checkout. Try again or contact staff."));
            return true;
        }

        // Paystack requires an email; Minecraft accounts don't have one, so we use a
        // deterministic placeholder tied to the player's UUID. Paystack's dashboard
        // will show this instead of a real address - that's expected and fine, it's
        // only used as a receipt identifier, not for actually emailing the player.
        String placeholderEmail = player.getUniqueId() + "@ghanarealms.players";
        String currency = plugin.getConfig().getString("PAYSTACK.CURRENCY", "GHS");
        String callbackUrl = plugin.getConfig().getString("PAYSTACK.CALLBACK-URL", "");

        PaystackClient.InitResult result = plugin.getClient()
                .initializeTransaction(placeholderEmail, priceGhs, reference, currency, callbackUrl);

        if (!result.ok()) {
            player.sendMessage(color("&cCould not create checkout: " + result.message()));
            return true;
        }

        List<String> lines = plugin.getConfig().getStringList("MESSAGES.CHECKOUT-CREATED");
        for (String line : lines) {
            player.sendMessage(color(line
                    .replace("{reference}", result.reference())
                    .replace("{authorization_url}", result.authorizationUrl())));
        }
        return true;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
