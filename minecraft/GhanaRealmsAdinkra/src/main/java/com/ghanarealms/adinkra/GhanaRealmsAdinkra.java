package com.ghanarealms.adinkra;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;

import io.papermc.paper.chat.ChatRenderer;
import java.sql.*;
import java.util.*;

public class GhanaRealmsAdinkra extends JavaPlugin implements CommandExecutor, Listener {
    private Connection db;
    private final LegacyComponentSerializer amp = LegacyComponentSerializer.legacyAmpersand();
    private final Random rnd = new Random();

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            getDataFolder().mkdirs();
            db = DriverManager.getConnection("jdbc:sqlite:" + getDataFolder() + "/adinkra.db");
            try (Statement s = db.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS owned(uuid TEXT, symbol TEXT, PRIMARY KEY(uuid,symbol))");
                s.execute("CREATE TABLE IF NOT EXISTS selected(uuid TEXT PRIMARY KEY, symbol TEXT)");
            }
        } catch (SQLException e) { getLogger().severe(e.toString()); getServer().getPluginManager().disablePlugin(this); return; }
        getCommand("adinkra").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }
    @Override public void onDisable() { try { if (db != null) db.close(); } catch (SQLException ignored) {} }

    private boolean known(String id) { return getConfig().getConfigurationSection("SYMBOLS") != null && getConfig().getConfigurationSection("SYMBOLS").contains(id); }
    private boolean owns(UUID u, String id) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("SELECT 1 FROM owned WHERE uuid=? AND symbol=?")) { p.setString(1, u.toString()); p.setString(2, id); return p.executeQuery().next(); }
    }
    private String selected(UUID u) {
        try (PreparedStatement p = db.prepareStatement("SELECT symbol FROM selected WHERE uuid=?")) { p.setString(1, u.toString()); ResultSet r = p.executeQuery(); return r.next() ? r.getString(1) : null; }
        catch (SQLException e) { return null; }
    }
    private void run(String sql, String... a) throws SQLException {
        try (PreparedStatement p = db.prepareStatement(sql)) { for (int i = 0; i < a.length; i++) p.setString(i + 1, a[i]); p.executeUpdate(); }
    }
    private String titleOf(String id) { return getConfig().getString("SYMBOLS." + id + ".title", ""); }

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        try {
            String sub = a.length == 0 ? "list" : a[0].toLowerCase();
            if (sub.equals("grant") || sub.equals("revoke")) {
                if (!s.hasPermission("ghanarealmsadinkra.admin")) { s.sendMessage("No permission."); return true; }
                if (a.length < 3 || !known(a[2])) { s.sendMessage("/adinkra " + sub + " <player> <symbolId>"); return true; }
                OfflinePlayer t = Bukkit.getOfflinePlayer(a[1]);
                if (sub.equals("grant")) {
                    run("INSERT OR IGNORE INTO owned VALUES(?,?)", t.getUniqueId().toString(), a[2]);
                    if (selected(t.getUniqueId()) == null) run("INSERT OR REPLACE INTO selected VALUES(?,?)", t.getUniqueId().toString(), a[2]);
                    if (t.isOnline()) t.getPlayer().sendMessage(amp.deserialize("&6You unlocked the " + titleOf(a[2]) + " &6title! /adinkra"));
                } else {
                    run("DELETE FROM owned WHERE uuid=? AND symbol=?", t.getUniqueId().toString(), a[2]);
                    if (a[2].equals(selected(t.getUniqueId()))) run("DELETE FROM selected WHERE uuid=?", t.getUniqueId().toString());
                }
                s.sendMessage(sub + " ok: " + a[1] + " " + a[2]);
                return true;
            }
            if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
            switch (sub) {
                case "select" -> {
                    if (a.length < 2 || !known(a[1]) || !owns(p.getUniqueId(), a[1])) { p.sendMessage("You don't own that title. /adinkra list"); return true; }
                    run("INSERT OR REPLACE INTO selected VALUES(?,?)", p.getUniqueId().toString(), a[1]);
                    p.sendMessage(amp.deserialize("&aTitle set: " + titleOf(a[1])));
                }
                case "clear" -> { run("DELETE FROM selected WHERE uuid=?", p.getUniqueId().toString()); p.sendMessage("Title cleared."); }
                default -> {
                    p.sendMessage("Your Adinkra titles (/adinkra select <id>):");
                    ConfigurationSection sec = getConfig().getConfigurationSection("SYMBOLS");
                    boolean any = false;
                    if (sec != null) for (String id : sec.getKeys(false)) if (owns(p.getUniqueId(), id)) { any = true; p.sendMessage(amp.deserialize(" &7" + id + " " + titleOf(id))); }
                    if (!any) p.sendMessage("None yet - get them in the store.");
                }
            }
        } catch (SQLException e) { s.sendMessage("Database error."); getLogger().warning(e.toString()); }
        return true;
    }

    @EventHandler public void onChat(AsyncChatEvent e) {
        if (!getConfig().getBoolean("CHAT-TITLES", true)) return;
        String id = selected(e.getPlayer().getUniqueId());
        if (id == null || !known(id)) return;
        Component prefix = amp.deserialize(titleOf(id) + " ");
        e.renderer(ChatRenderer.viewerUnaware((src, name, msg) ->
            prefix.append(name).append(Component.text(": ")).append(msg)));
    }

    @EventHandler public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (getConfig().getBoolean("JOIN-MESSAGES", true)) {
            List<String> lines = getConfig().getStringList("JOIN-LINES");
            String line = !p.hasPlayedBefore() ? getConfig().getString("FIRST-JOIN-LINE") : (lines.isEmpty() ? null : lines.get(rnd.nextInt(lines.size())));
            if (line != null) e.joinMessage(amp.deserialize(line.replace("{player}", p.getName())));
        }
        String id = selected(p.getUniqueId());
        if (id != null) {
            String eff = getConfig().getString("SYMBOLS." + id + ".effect", "");
            PotionEffectType t = eff.isEmpty() ? null : PotionEffectType.getByName(eff.toUpperCase());
            if (t != null) p.addPotionEffect(new PotionEffect(t, PotionEffect.INFINITE_DURATION, 0, true, false));
        }
    }

    @EventHandler public void onCmd(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage();
        String[] parts = msg.substring(1).split(" ", 2);
        String target = getConfig().getString("ALIASES." + parts[0].toLowerCase());
        if (target != null && !target.isEmpty()) e.setMessage("/" + target + (parts.length > 1 ? " " + parts[1] : ""));
    }
}
