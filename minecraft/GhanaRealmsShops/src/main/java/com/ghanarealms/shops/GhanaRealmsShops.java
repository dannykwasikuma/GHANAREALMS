package com.ghanarealms.shops;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class GhanaRealmsShops extends JavaPlugin {

    private Economy economy;
    private ShopStore store;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new ShopStore(this);
        setupVault();

        Bukkit.getPluginManager().registerEvents(new ShopListener(this, store, economy), this);

        // Walk-in server shops: registered chests open a buy/sell menu (unlimited stock, Vault money).
        ServerShops serverShops = new ServerShops(this);
        ServerShopListener serverListener = new ServerShopListener(this, serverShops, economy);
        Bukkit.getPluginManager().registerEvents(serverListener, this);
        var serverShopCmd = new ServerShopCommand(this, serverShops, store, serverListener);
        getCommand("servershop").setExecutor(serverShopCmd);
        getCommand("servershop").setTabCompleter(serverShopCmd);
        getCommand("shophelp").setExecutor((sender, cmd, label, args) -> {
            for (String line : getConfig().getStringList("MESSAGES.HELP")) {
                sender.sendMessage(ChatColor.translateAlternateColorCodes('&', line));
            }
            return true;
        });

        getLogger().info("GhanaRealmsShops enabled with " + store.all().size() + " existing shop(s).");
    }

    private void setupVault() {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault not found - shops are disabled until it's installed.");
            return;
        }
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp != null) economy = rsp.getProvider();
    }
}
