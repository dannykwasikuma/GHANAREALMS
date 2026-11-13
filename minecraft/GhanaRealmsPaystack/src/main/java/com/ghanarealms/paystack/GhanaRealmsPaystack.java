package com.ghanarealms.paystack;

import com.ghanarealms.paystack.storage.PurchaseStore;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.logging.Level;

public class GhanaRealmsPaystack extends JavaPlugin {

    private PurchaseStore store;
    private PaystackClient client;
    private WebhookServer webhookServer;
    private Economy economy;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String secretKey = getConfig().getString("PAYSTACK.SECRET-KEY", "");
        if (secretKey.isBlank() || secretKey.contains("REPLACE_ME")) {
            getLogger().severe("PAYSTACK.SECRET-KEY is not set in config.yml - payments will not work until you set it.");
        }

        store = new PurchaseStore(this);
        store.connect(getConfig().getString("DATABASE.FILE", "purchases.db"));

        client = new PaystackClient(secretKey, getLogger());

        setupVault();

        if (getConfig().getBoolean("WEBHOOK.ENABLED", true)) {
            webhookServer = new WebhookServer(this, store, client, secretKey, this::deliverPurchase);
            webhookServer.start(getConfig().getInt("WEBHOOK.PORT", 8085), getConfig().getString("WEBHOOK.PATH", "/ghanarealms/paystack/webhook"));
        }

        setupBridge();

        getCommand("buy").setExecutor(new BuyCommand(this));
        getCommand("paystack").setExecutor(new PaystackAdminCommand(this));

        getLogger().info("GhanaRealmsPaystack enabled.");
    }

    @Override
    public void onDisable() {
        if (webhookServer != null) webhookServer.stop();
        if (store != null) store.close();
    }

    private void setupVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found - GIVE-MONEY on packages will be skipped. Console commands still run.");
            return;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) {
            economy = rsp.getProvider();
            getLogger().info("Hooked into Vault economy: " + economy.getName());
        } else {
            getLogger().warning("Vault is present but no economy plugin is registered with it yet.");
        }
    }

    private BridgeClient bridgeClient;

    private void setupBridge() {
        if (!getConfig().getBoolean("BRIDGE.ENABLED", false)) return;

        bridgeClient = new BridgeClient(
                this, economy,
                getConfig().getString("BRIDGE.STORE-BASE-URL", ""),
                getConfig().getString("BRIDGE.BRIDGE-SECRET", "")
        );
        if (!bridgeClient.isConfigured()) {
            getLogger().warning("BRIDGE.ENABLED is true but STORE-BASE-URL/BRIDGE-SECRET are not set - website purchase delivery is disabled.");
            return;
        }

        int intervalTicks = getConfig().getInt("BRIDGE.POLL-INTERVAL-SECONDS", 30) * 20;
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> bridgeClient.pollAndDeliver(), 100L, intervalTicks);
        getLogger().info("Web store bridge enabled - polling every " + getConfig().getInt("BRIDGE.POLL-INTERVAL-SECONDS", 30) + "s.");
    }

    public PurchaseStore getStore() {
        return store;
    }

    public PaystackClient getClient() {
        return client;
    }

    /** Called (on the main thread) once a transaction has been verified SUCCESS
     *  and marked so exactly once via PurchaseStore.markSuccessOnce. */
    public void deliverPurchase(PurchaseStore.Purchase purchase) {
        ConfigurationSection pkgSection = getConfig().getConfigurationSection("PACKAGES." + purchase.packageId());
        if (pkgSection == null) {
            getLogger().severe("Delivering purchase " + purchase.reference() + " but package '" + purchase.packageId() + "' no longer exists in config!");
            return;
        }

        OfflinePlayer target;
        try {
            target = Bukkit.getOfflinePlayer(UUID.fromString(purchase.playerUuid()));
        } catch (Exception e) {
            target = Bukkit.getOfflinePlayer(purchase.playerName());
        }

        double giveMoney = pkgSection.getDouble("GIVE-MONEY", 0);
        if (giveMoney > 0) {
            giveEconomy(target, giveMoney);
        }

        String playerName = target.getName() == null ? purchase.playerName() : target.getName();
        for (String cmd : pkgSection.getStringList("COMMANDS")) {
            String resolved = cmd.replace("{player}", playerName);
            Bukkit.getScheduler().runTask(this, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved));
        }

        if (target.isOnline() && target.getPlayer() != null) {
            String msg = getConfig().getString("MESSAGES.PAYMENT-SUCCESS", "&aPayment confirmed!")
                    .replace("{player}", playerName);
            target.getPlayer().sendMessage(org.bukkit.ChatColor.translateAlternateColorCodes('&', msg));
        }

        getLogger().info("Delivered package '" + purchase.packageId() + "' to " + purchase.playerName() + " for reference " + purchase.reference());
    }

    private void giveEconomy(OfflinePlayer target, double amount) {
        // Deliberately isolated in its own try/catch: if Vault or the underlying
        // economy plugin isn't present, the rest of delivery (rank commands) should
        // still run rather than the whole purchase silently failing.
        try {
            if (economy == null) {
                getLogger().warning("Vault economy not available - skipped giving " + amount);
                return;
            }
            economy.depositPlayer(target, amount);
        } catch (Throwable t) {
            getLogger().log(Level.WARNING, "Failed to deposit via Vault economy", t);
        }
    }
}
