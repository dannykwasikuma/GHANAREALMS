package com.ghanarealms.backpack;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.util.UUID;

public class GhanaRealmsBackpack extends JavaPlugin implements CommandExecutor, Listener {
    private Connection db;

    private static class Holder implements InventoryHolder {
        Inventory inv; final UUID owner; Holder(UUID o) { owner = o; }
        @Override public Inventory getInventory() { return inv; }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            getDataFolder().mkdirs();
            db = DriverManager.getConnection("jdbc:sqlite:" + getDataFolder() + "/backpacks.db");
            try (Statement s = db.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS backpacks(uuid TEXT PRIMARY KEY, rows INTEGER NOT NULL, data BLOB)");
            }
        } catch (SQLException e) { getLogger().severe(e.toString()); getServer().getPluginManager().disablePlugin(this); return; }
        getCommand("backpack").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }
    @Override public void onDisable() { try { if (db != null) db.close(); } catch (SQLException ignored) {} }

    private int rows(UUID u) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("SELECT rows FROM backpacks WHERE uuid=?")) {
            p.setString(1, u.toString()); ResultSet r = p.executeQuery();
            int def = Math.min(6, Math.max(1, getConfig().getInt("DEFAULT-ROWS", 1)));
            return r.next() ? Math.max(def, r.getInt(1)) : def;
        }
    }
    private byte[] data(UUID u) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("SELECT data FROM backpacks WHERE uuid=?")) {
            p.setString(1, u.toString()); ResultSet r = p.executeQuery(); return r.next() ? r.getBytes(1) : null;
        }
    }
    private void save(UUID u, int rows, byte[] data) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("INSERT INTO backpacks(uuid,rows,data) VALUES(?,?,?) ON CONFLICT(uuid) DO UPDATE SET rows=excluded.rows, data=excluded.data")) {
            p.setString(1, u.toString()); p.setInt(2, rows); p.setBytes(3, data); p.executeUpdate();
        }
    }

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        try {
            if (a.length > 0 && a[0].equalsIgnoreCase("setrows")) {
                if (!s.hasPermission("ghanarealmsbackpack.admin")) { s.sendMessage("No permission."); return true; }
                if (a.length < 3) { s.sendMessage("/backpack setrows <player> <1-6>"); return true; }
                int n; try { n = Integer.parseInt(a[2]); } catch (NumberFormatException e) { s.sendMessage("Rows must be 1-6."); return true; }
                if (n < 1 || n > 6) { s.sendMessage("Rows must be 1-6."); return true; }
                OfflinePlayer t = Bukkit.getOfflinePlayer(a[1]);
                int cur = rows(t.getUniqueId());
                if (n <= cur) { s.sendMessage(a[1] + " already has " + cur + " rows (upgrade-only; not lowering)."); return true; }
                save(t.getUniqueId(), n, data(t.getUniqueId()));
                s.sendMessage(a[1] + " backpack -> " + n + " rows.");
                if (t.isOnline()) t.getPlayer().sendMessage("Your backpack was upgraded to " + n + " rows!");
                return true;
            }
            if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
            if (!p.hasPermission("ghanarealmsbackpack.use")) { p.sendMessage("No permission."); return true; }
            Holder h = new Holder(p.getUniqueId());
            int r = rows(p.getUniqueId());
            h.inv = Bukkit.createInventory(h, r * 9, net.kyori.adventure.text.Component.text("Backpack"));
            byte[] d = data(p.getUniqueId());
            if (d != null && d.length > 0) {
                ItemStack[] items = ItemStack.deserializeItemsFromBytes(d);
                for (int i = 0; i < items.length && i < h.inv.getSize(); i++) h.inv.setItem(i, items[i]);
            }
            p.openInventory(h.inv);
        } catch (SQLException e) { s.sendMessage("Database error."); getLogger().warning(e.toString()); }
        return true;
    }

    @EventHandler public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        try { save(h.owner, rows(h.owner), ItemStack.serializeItemsAsBytes(e.getInventory().getContents())); }
        catch (SQLException ex) { getLogger().severe("Failed saving backpack for " + h.owner + ": " + ex); }
    }
}
