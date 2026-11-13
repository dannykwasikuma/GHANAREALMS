package com.ghanarealms.clans;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.util.*;

public class GhanaRealmsClans extends JavaPlugin implements CommandExecutor {
    private Connection db;
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<UUID, String> invites = new HashMap<>(); // invitee -> tag

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            getDataFolder().mkdirs();
            db = DriverManager.getConnection("jdbc:sqlite:" + getDataFolder() + "/clans.db");
            try (Statement s = db.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS clans(tag TEXT PRIMARY KEY, name TEXT, leader TEXT)");
                s.execute("CREATE TABLE IF NOT EXISTS members(uuid TEXT PRIMARY KEY, name TEXT, tag TEXT)");
            }
        } catch (SQLException e) { getLogger().severe("DB error: " + e); getServer().getPluginManager().disablePlugin(this); return; }
        getCommand("clan").setExecutor(this);
        getCommand("clanvote").setExecutor(this);
        long ticks = 20L * Math.max(30, getConfig().getInt("BRIDGE.SYNC-INTERVAL-SECONDS", 300));
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::sync, 100L, ticks);
    }

    @Override public void onDisable() { try { if (db != null) db.close(); } catch (SQLException ignored) {} }

    private String esc(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }
    private boolean bridgeOn() { return getConfig().getBoolean("BRIDGE.ENABLED") ; }

    private String post(String path, String json) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create(getConfig().getString("BRIDGE.STORE-BASE-URL", "") + path))
            .header("content-type", "application/json")
            .header("x-bridge-secret", getConfig().getString("BRIDGE.BRIDGE-SECRET", ""))
            .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        HttpResponse<String> res = http.send(r, HttpResponse.BodyHandlers.ofString());
        return res.statusCode() + " " + res.body();
    }

    /** Full-roster sync; the store treats absent clans/members as gone. */
    private synchronized void sync() {
        if (!bridgeOn()) return;
        try {
            StringBuilder sb = new StringBuilder("{\"clans\":[");
            try (Statement s = db.createStatement(); ResultSet c = s.executeQuery("SELECT c.tag,c.name,c.leader,(SELECT name FROM members WHERE uuid=c.leader) ln FROM clans c")) {
                boolean first = true;
                while (c.next()) {
                    if (!first) sb.append(','); first = false;
                    sb.append("{\"tag\":").append(esc(c.getString(1))).append(",\"name\":").append(esc(c.getString(2)))
                      .append(",\"leaderMinecraftUuid\":").append(esc(c.getString(3)))
                      .append(",\"leaderMinecraftUsername\":").append(esc(c.getString(4) == null ? "unknown" : c.getString(4)))
                      .append(",\"members\":[");
                    try (PreparedStatement p = db.prepareStatement("SELECT uuid,name FROM members WHERE tag=?")) {
                        p.setString(1, c.getString(1));
                        ResultSet m = p.executeQuery(); boolean f2 = true;
                        while (m.next()) {
                            if (!f2) sb.append(','); f2 = false;
                            sb.append("{\"minecraftUuid\":").append(esc(m.getString(1))).append(",\"minecraftUsername\":").append(esc(m.getString(2))).append('}');
                        }
                    }
                    sb.append("]}");
                }
            }
            sb.append("]}");
            String out = post("/api/bridge/clans/sync", sb.toString());
            if (!out.startsWith("200")) getLogger().warning("Clan sync: " + out);
        } catch (Exception e) { getLogger().warning("Clan sync failed: " + e); }
    }
    private void syncAsync() { Bukkit.getScheduler().runTaskAsynchronously(this, this::sync); }

    private String clanOf(UUID u) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("SELECT tag FROM members WHERE uuid=?")) {
            p.setString(1, u.toString()); ResultSet r = p.executeQuery(); return r.next() ? r.getString(1) : null;
        }
    }
    private boolean isLeader(UUID u, String tag) throws SQLException {
        try (PreparedStatement p = db.prepareStatement("SELECT 1 FROM clans WHERE tag=? AND leader=?")) {
            p.setString(1, tag); p.setString(2, u.toString()); return p.executeQuery().next();
        }
    }
    private void exec(String sql, String... a) throws SQLException {
        try (PreparedStatement p = db.prepareStatement(sql)) { for (int i = 0; i < a.length; i++) p.setString(i + 1, a[i]); p.executeUpdate(); }
    }

    @Override public boolean onCommand(CommandSender snd, Command cmd, String label, String[] a) {
        if (!(snd instanceof Player pl)) { snd.sendMessage("Players only."); return true; }
        try {
            if (cmd.getName().equals("clanvote")) { vote(pl, a); return true; }
            if (a.length == 0) { pl.sendMessage("/clan <create|invite|accept|leave|disband|info>"); return true; }
            String mine = clanOf(pl.getUniqueId());
            switch (a[0].toLowerCase()) {
                case "create" -> {
                    if (a.length < 3) { pl.sendMessage("/clan create <tag> <name>"); return true; }
                    String tag = a[1].toUpperCase();
                    if (mine != null) { pl.sendMessage("Leave your clan first."); return true; }
                    if (!tag.matches("[A-Z0-9]{2," + getConfig().getInt("CLANS.MAX-TAG-LENGTH", 5) + "}")) { pl.sendMessage("Tag must be letters/numbers."); return true; }
                    try (PreparedStatement p = db.prepareStatement("SELECT 1 FROM clans WHERE tag=?")) { p.setString(1, tag); if (p.executeQuery().next()) { pl.sendMessage("Tag taken."); return true; } }
                    exec("INSERT INTO clans VALUES(?,?,?)", tag, String.join(" ", Arrays.copyOfRange(a, 2, a.length)), pl.getUniqueId().toString());
                    exec("INSERT INTO members VALUES(?,?,?)", pl.getUniqueId().toString(), pl.getName(), tag);
                    pl.sendMessage("Clan " + tag + " created."); syncAsync();
                }
                case "invite" -> {
                    if (mine == null || !isLeader(pl.getUniqueId(), mine)) { pl.sendMessage("Only a clan leader can invite."); return true; }
                    Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                    if (t == null) { pl.sendMessage("Player not online."); return true; }
                    try (PreparedStatement p = db.prepareStatement("SELECT count(*) FROM members WHERE tag=?")) {
                        p.setString(1, mine); ResultSet r = p.executeQuery();
                        if (r.next() && r.getInt(1) >= getConfig().getInt("CLANS.MAX-MEMBERS", 20)) { pl.sendMessage("Clan is full."); return true; }
                    }
                    invites.put(t.getUniqueId(), mine);
                    t.sendMessage(pl.getName() + " invited you to " + mine + ". /clan accept");
                    pl.sendMessage("Invite sent.");
                }
                case "accept" -> {
                    String tag = invites.remove(pl.getUniqueId());
                    if (tag == null) { pl.sendMessage("No pending invite."); return true; }
                    if (mine != null) { pl.sendMessage("Leave your clan first."); return true; }
                    exec("INSERT INTO members VALUES(?,?,?)", pl.getUniqueId().toString(), pl.getName(), tag);
                    pl.sendMessage("Joined " + tag + "."); syncAsync();
                }
                case "leave" -> {
                    if (mine == null) { pl.sendMessage("You're not in a clan."); return true; }
                    if (isLeader(pl.getUniqueId(), mine)) { pl.sendMessage("Leaders must /clan disband."); return true; }
                    exec("DELETE FROM members WHERE uuid=?", pl.getUniqueId().toString());
                    pl.sendMessage("Left " + mine + "."); syncAsync();
                }
                case "disband" -> {
                    if (mine == null || !isLeader(pl.getUniqueId(), mine)) { pl.sendMessage("Only a clan leader can disband."); return true; }
                    exec("DELETE FROM members WHERE tag=?", mine); exec("DELETE FROM clans WHERE tag=?", mine);
                    pl.sendMessage("Clan disbanded."); syncAsync();
                }
                case "info" -> {
                    if (mine == null) { pl.sendMessage("You're not in a clan."); return true; }
                    pl.sendMessage("Clan " + mine + (isLeader(pl.getUniqueId(), mine) ? " (leader)" : ""));
                }
                default -> pl.sendMessage("Unknown subcommand.");
            }
        } catch (SQLException e) { pl.sendMessage("Database error."); getLogger().warning(e.toString()); }
        return true;
    }

    private void vote(Player pl, String[] a) {
        if (a.length < 1) { pl.sendMessage("/clanvote <clanTag>"); return; }
        if (!bridgeOn()) { pl.sendMessage("Voting is unavailable."); return; }
        String tag = a[0].toUpperCase();
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            for (String type : new String[]{"WEEKLY", "MONTHLY"}) {
                String body = "{\"voterMinecraftUuid\":" + esc(pl.getUniqueId().toString()) + ",\"voterMinecraftUsername\":" + esc(pl.getName())
                    + ",\"clanTag\":" + esc(tag) + ",\"periodType\":" + esc(type) + "}";
                String out;
                try { out = post("/api/bridge/clans/vote", body); } catch (Exception e) { out = "error " + e.getMessage(); }
                String res = out;
                Bukkit.getScheduler().runTask(this, () -> pl.sendMessage(type + ": " + (res.startsWith("200") || res.startsWith("201") ? "vote recorded (or already voted)." : "rejected - " + res)));
            }
        });
    }
}
