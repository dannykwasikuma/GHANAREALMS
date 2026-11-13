package com.ghanarealms.raids;

import org.bukkit.*;
import org.bukkit.boss.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class GhanaRealmsRaids extends JavaPlugin implements CommandExecutor, Listener {
    private NamespacedKey TAG;
    private final Random rnd = new Random();

    private static class Raid {
        UUID target; String targetName; int wave = 0, waveTotal = 1; long deadline; Location anchor;
        Set<UUID> mobs = new HashSet<>(); BossBar bar;
    }
    private final List<Raid> raids = new ArrayList<>();

    private boolean apocActive; private long apocEnd, lastApocEnd; private BossBar apocBar;
    private final Map<UUID, Integer> kills = new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        TAG = new NamespacedKey(this, "event_mob");
        getCommand("raid").setExecutor(this); getCommand("apocalypse").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::minuteRoll, 1200L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, this::raidTick, 20L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::apocTick, 100L, 100L);
        Bukkit.getScheduler().runTaskTimer(this, this::apocBarTick, 20L, 20L);
    }
    @Override public void onDisable() {
        for (Raid r : new ArrayList<>(raids)) endRaid(r, false);
        if (apocActive) endApoc(false);
        purgeTagged(null);
    }

    // ---------- helpers
    private boolean eligible(Player p) {
        if (!getConfig().getStringList("WORLDS").contains(p.getWorld().getName())) return false;
        if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) return false;
        Location sp = p.getWorld().getSpawnLocation();
        return p.getLocation().distance(sp) >= getConfig().getDouble("MIN-DISTANCE-FROM-SPAWN", 200);
    }
    private List<Player> eligiblePlayers() { List<Player> l = new ArrayList<>(); for (Player p : Bukkit.getOnlinePlayers()) if (eligible(p)) l.add(p); return l; }

    private EntityType weighted(List<String> entries) {
        int total = 0; for (String s : entries) total += Integer.parseInt(s.split(":")[1]);
        int r = rnd.nextInt(Math.max(1, total));
        for (String s : entries) { String[] p = s.split(":"); r -= Integer.parseInt(p[1]); if (r < 0) return EntityType.valueOf(p[0].toUpperCase()); }
        return EntityType.ZOMBIE;
    }

    private Location ringSpot(Location c, double min, double max) {
        for (int i = 0; i < 12; i++) {
            double ang = rnd.nextDouble() * Math.PI * 2, d = min + rnd.nextDouble() * (max - min);
            int x = (int) (c.getX() + Math.cos(ang) * d), z = (int) (c.getZ() + Math.sin(ang) * d);
            World w = c.getWorld(); int y = w.getHighestBlockYAt(x, z);
            org.bukkit.block.Block g = w.getBlockAt(x, y, z);
            if (g.isLiquid() || g.getType().name().endsWith("LEAVES")) continue;
            if (!w.getBlockAt(x, y + 1, z).getType().isAir() || !w.getBlockAt(x, y + 2, z).getType().isAir()) continue;
            return new Location(w, x + 0.5, y + 1, z + 0.5);
        }
        return null;
    }
    private LivingEntity spawn(EntityType t, Location l, String tag) {
        Entity e = l.getWorld().spawnEntity(l, t);
        if (!(e instanceof LivingEntity le)) { e.remove(); return null; }
        le.getPersistentDataContainer().set(TAG, PersistentDataType.STRING, tag);
        le.setPersistent(false); le.setRemoveWhenFarAway(true);
        return le;
    }
    private void purgeTagged(String tag) {
        for (World w : Bukkit.getWorlds()) for (LivingEntity le : w.getLivingEntities()) {
            String t = le.getPersistentDataContainer().get(TAG, PersistentDataType.STRING);
            if (t != null && (tag == null || t.equals(tag))) le.remove();
        }
    }
    private void give(Player p, ConfigurationSection s) {
        if (s == null) return;
        for (String it : s.getStringList("ITEMS")) {
            String[] a = it.split(":"); Material m = Material.matchMaterial(a[0]); if (m == null) continue;
            String[] r = (a.length > 1 ? a[1] : "1").split("-");
            int lo = Integer.parseInt(r[0]), hi = r.length > 1 ? Integer.parseInt(r[1]) : lo;
            int amt = Math.min(m.getMaxStackSize(), lo + rnd.nextInt(Math.max(1, hi - lo + 1)));
            p.getInventory().addItem(new ItemStack(m, amt)).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
        }
        for (String c : s.getStringList("COMMANDS")) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("{player}", p.getName()));
    }

    // ---------- scheduling
    private void minuteRoll() {
        List<Player> el = eligiblePlayers(); if (el.isEmpty()) return;
        if (getConfig().getBoolean("RAIDS.ENABLED", true) && raids.size() < getConfig().getInt("RAIDS.MAX-CONCURRENT", 2)
                && rnd.nextDouble() < getConfig().getDouble("RAIDS.CHANCE-PER-MINUTE", 0.04)) {
            Player t = el.get(rnd.nextInt(el.size()));
            if (raids.stream().noneMatch(r -> r.target.equals(t.getUniqueId()))) startRaid(t);
        }
        long cool = 3_600_000L * getConfig().getLong("APOCALYPSE.COOLDOWN-HOURS", 3);
        if (!apocActive && getConfig().getBoolean("APOCALYPSE.ENABLED", true) && System.currentTimeMillis() - lastApocEnd > cool
                && rnd.nextDouble() < getConfig().getDouble("APOCALYPSE.CHANCE-PER-MINUTE", 0.004)) startApoc();
    }

    // ---------- raids
    private void startRaid(Player t) {
        Raid r = new Raid(); r.target = t.getUniqueId(); r.targetName = t.getName(); r.anchor = t.getLocation().clone();
        r.deadline = System.currentTimeMillis() + 60_000L * getConfig().getInt("RAIDS.TIME-LIMIT-MINUTES", 10);
        r.bar = Bukkit.createBossBar("Raid on " + t.getName(), BarColor.RED, BarStyle.SEGMENTED_10);
        raids.add(r);
        Bukkit.broadcastMessage("§c[Raid] §fA raid is attacking §e" + t.getName() + "§f! Help defend for a reward!");
        t.sendTitle("§cRAID!", "§fDefend yourself!", 10, 60, 10);
        nextWave(r);
    }
    private void nextWave(Raid r) {
        Player t = Bukkit.getPlayer(r.target); if (t == null) { endRaid(r, false); return; }
        r.wave++;
        List<Integer> counts = getConfig().getIntegerList("RAIDS.MOBS-PER-WAVE");
        int n = counts.isEmpty() ? 6 : counts.get(Math.min(r.wave - 1, counts.size() - 1));
        List<?> tables = getConfig().getList("RAIDS.WAVE-MOBS", List.of());
        @SuppressWarnings("unchecked") List<String> table = tables.isEmpty() ? List.of("ZOMBIE:1") : (List<String>) tables.get(Math.min(r.wave - 1, tables.size() - 1));
        r.mobs.clear();
        for (int i = 0; i < n; i++) {
            Location l = ringSpot(t.getLocation(), 12, 22); if (l == null) continue;
            LivingEntity le = spawn(weighted(table), l, "raid");
            if (le == null) continue;
            if (le instanceof Mob m) m.setTarget(t);
            r.mobs.add(le.getUniqueId());
        }
        r.waveTotal = Math.max(1, r.mobs.size());
        t.sendMessage("§c[Raid] Wave " + r.wave + "/" + getConfig().getInt("RAIDS.WAVES", 3) + "!");
        if (r.mobs.isEmpty()) endRaid(r, false); // nowhere to spawn: abort, don't strand the player
    }
    private void raidTick() {
        for (Raid r : new ArrayList<>(raids)) {
            Player t = Bukkit.getPlayer(r.target);
            if (t == null || t.isDead() || System.currentTimeMillis() > r.deadline) { endRaid(r, false); continue; }
            r.mobs.removeIf(id -> { Entity e = Bukkit.getEntity(id); return e == null || e.isDead() || !e.isValid(); });
            r.bar.setTitle("§cRaid on " + r.targetName + " - wave " + r.wave + "/" + getConfig().getInt("RAIDS.WAVES", 3) + " (" + r.mobs.size() + " left)");
            r.bar.setProgress(Math.min(1.0, Math.max(0.0, r.mobs.size() / (double) r.waveTotal)));
            for (Player p : t.getWorld().getPlayers()) { if (p.getLocation().distance(t.getLocation()) < 60) r.bar.addPlayer(p); else r.bar.removePlayer(p); }
            for (UUID id : r.mobs) if (Bukkit.getEntity(id) instanceof Mob m && m.getTarget() == null) m.setTarget(t);
            if (r.mobs.isEmpty()) { if (r.wave >= getConfig().getInt("RAIDS.WAVES", 3)) endRaid(r, true); else nextWave(r); }
        }
    }
    private void endRaid(Raid r, boolean won) {
        raids.remove(r); r.bar.removeAll();
        for (UUID id : r.mobs) { Entity e = Bukkit.getEntity(id); if (e != null) e.remove(); }
        Player t = Bukkit.getPlayer(r.target);
        if (won && t != null) {
            double rad = getConfig().getDouble("RAIDS.REWARD-RADIUS", 40);
            Bukkit.broadcastMessage("§a[Raid] §e" + r.targetName + " §fsurvived the raid!");
            for (Player p : t.getWorld().getPlayers()) if (p.getLocation().distance(t.getLocation()) <= rad && !p.isDead()) { give(p, getConfig().getConfigurationSection("RAIDS.REWARD")); p.sendMessage("§a[Raid] Reward received!"); }
        } else Bukkit.broadcastMessage("§7[Raid] The raid on " + r.targetName + " has ended.");
    }

    // ---------- apocalypse
    private void startApoc() {
        apocActive = true; kills.clear();
        apocEnd = System.currentTimeMillis() + 60_000L * getConfig().getInt("APOCALYPSE.DURATION-MINUTES", 10);
        apocBar = Bukkit.createBossBar("§4MOB APOCALYPSE", BarColor.PURPLE, BarStyle.SOLID);
        for (Player p : Bukkit.getOnlinePlayers()) { apocBar.addPlayer(p); p.sendTitle("§4MOB APOCALYPSE", "§fSurvive and kill as many as you can!", 10, 80, 20); }
        Bukkit.broadcastMessage("§4[Apocalypse] §fThe dead are rising! Kill event mobs - top 3 killers win big. Lasts " + getConfig().getInt("APOCALYPSE.DURATION-MINUTES", 10) + " minutes.");
        if (getConfig().getBoolean("APOCALYPSE.FORCE-NIGHT-AND-STORM", true))
            for (String n : getConfig().getStringList("WORLDS")) { World w = Bukkit.getWorld(n); if (w != null) { w.setTime(14000); w.setStorm(true); } }
    }
    private void apocBarTick() {
        if (!apocActive) return;
        long left = apocEnd - System.currentTimeMillis();
        if (left <= 0) { endApoc(true); return; }
        long total = 60_000L * getConfig().getInt("APOCALYPSE.DURATION-MINUTES", 10);
        apocBar.setProgress(Math.max(0, Math.min(1, left / (double) total)));
        apocBar.setTitle("§4MOB APOCALYPSE §f- " + left / 60000 + "m " + (left / 1000) % 60 + "s");
        for (Player p : Bukkit.getOnlinePlayers()) apocBar.addPlayer(p);
    }
    private void apocTick() {
        if (!apocActive) return;
        List<String> table = getConfig().getStringList("APOCALYPSE.MOBS");
        int cap = getConfig().getInt("APOCALYPSE.CAP-PER-PLAYER", 14), batch = getConfig().getInt("APOCALYPSE.BATCH-PER-PLAYER", 3);
        for (Player p : eligiblePlayers()) {
            long near = p.getNearbyEntities(40, 40, 40).stream().filter(e -> "apoc".equals(e.getPersistentDataContainer().get(TAG, PersistentDataType.STRING))).count();
            for (int i = 0; i < batch && near < cap; i++, near++) {
                Location l = ringSpot(p.getLocation(), 14, 28); if (l == null) continue;
                LivingEntity le = spawn(weighted(table), l, "apoc");
                if (le instanceof Mob m) m.setTarget(p);
            }
        }
    }
    private void endApoc(boolean natural) {
        apocActive = false; lastApocEnd = System.currentTimeMillis();
        if (apocBar != null) apocBar.removeAll();
        purgeTagged("apoc");
        if (!natural) { Bukkit.broadcastMessage("§7[Apocalypse] Ended by an admin."); return; }
        Bukkit.broadcastMessage("§a[Apocalypse] The dead fall silent. Survivors rejoice!");
        List<Map.Entry<UUID, Integer>> top = new ArrayList<>(kills.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        Set<UUID> rewarded = new HashSet<>(); int place = 0;
        for (var e : top) {
            Player p = Bukkit.getPlayer(e.getKey()); if (p == null) continue;
            if (place < 3) { place++; rewarded.add(e.getKey()); Bukkit.broadcastMessage("§6#" + place + " §e" + p.getName() + " §f- " + e.getValue() + " kills"); give(p, getConfig().getConfigurationSection("APOCALYPSE.REWARD-TOP")); }
        }
        int min = getConfig().getInt("APOCALYPSE.MIN-KILLS-FOR-PARTICIPANT", 10);
        for (var e : top) { Player p = Bukkit.getPlayer(e.getKey()); if (p != null && !rewarded.contains(e.getKey()) && e.getValue() >= min) { give(p, getConfig().getConfigurationSection("APOCALYPSE.REWARD-PARTICIPANT")); p.sendMessage("§a[Apocalypse] You earned a participation reward!"); } }
    }

    // ---------- events
    @EventHandler public void onDeath(EntityDeathEvent e) {
        String tag = e.getEntity().getPersistentDataContainer().get(TAG, PersistentDataType.STRING);
        if (tag == null) return;
        if (!getConfig().getBoolean("DROPS", true)) { e.getDrops().clear(); }
        if (tag.equals("apoc") && apocActive && e.getEntity().getKiller() != null) kills.merge(e.getEntity().getKiller().getUniqueId(), 1, Integer::sum);
    }
    @EventHandler public void onExplode(EntityExplodeEvent e) {
        if (getConfig().getBoolean("NO-BLOCK-DAMAGE", true) && e.getEntity().getPersistentDataContainer().has(TAG, PersistentDataType.STRING)) e.blockList().clear();
    }

    // ---------- commands
    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        String sub = a.length == 0 ? "status" : a[0].toLowerCase();
        if (c.getName().equals("raid")) {
            switch (sub) {
                case "start" -> {
                    Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : (s instanceof Player p ? p : null);
                    if (t == null) s.sendMessage("Player not online."); else { startRaid(t); s.sendMessage("Raid started."); }
                }
                case "stop" -> { for (Raid r : new ArrayList<>(raids)) endRaid(r, false); s.sendMessage("Raids stopped."); }
                default -> s.sendMessage(raids.size() + " active raid(s).");
            }
        } else {
            switch (sub) {
                case "start" -> { if (apocActive) s.sendMessage("Already active."); else { startApoc(); s.sendMessage("Apocalypse started."); } }
                case "stop" -> { if (apocActive) endApoc(false); s.sendMessage("Stopped."); }
                default -> s.sendMessage(apocActive ? "Active, " + (apocEnd - System.currentTimeMillis()) / 1000 + "s left." : "Not active.");
            }
        }
        return true;
    }
}
