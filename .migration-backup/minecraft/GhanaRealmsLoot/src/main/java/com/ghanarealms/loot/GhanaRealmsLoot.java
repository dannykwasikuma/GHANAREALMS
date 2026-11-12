package com.ghanarealms.loot;

import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class GhanaRealmsLoot extends JavaPlugin implements CommandExecutor, Listener {
    private static final List<String> TIERS = List.of("BASIC", "UNCOMMON", "RARE", "EPIC", "LEGENDARY");
    private static final Map<String, String> NAME = Map.of("BASIC", "Fugu", "UNCOMMON", "Kente", "RARE", "Adinkra", "EPIC", "Asante Gold", "LEGENDARY", "Golden Stool");
    private static final Map<String, Color> COLOR = Map.of("BASIC", Color.GRAY, "UNCOMMON", Color.GREEN, "RARE", Color.AQUA, "EPIC", Color.PURPLE, "LEGENDARY", Color.YELLOW);
    private static final Map<String, String> CHAT = Map.of("BASIC", "§7", "UNCOMMON", "§a", "RARE", "§b", "EPIC", "§d", "LEGENDARY", "§6");

    private record Active(Location loc, String rarity, long expiresAt) {}
    private final Map<String, Active> active = new HashMap<>(); // key "world;x;y;z"
    private final Random rnd = new Random();

    private static String key(Location l) { return l.getWorld().getName() + ";" + l.getBlockX() + ";" + l.getBlockY() + ";" + l.getBlockZ(); }

    @Override public void onEnable() {
        saveDefaultConfig();
        cleanupLeftovers();
        getCommand("lootchest").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        long period = 20L * 60 * Math.max(1, getConfig().getInt("SPAWN-INTERVAL-MINUTES", 20));
        Bukkit.getScheduler().runTaskTimer(this, () -> spawnOne(null), 200L, period);
        new BukkitRunnable() { @Override public void run() { tick(); } }.runTaskTimer(this, 40L, 40L);
    }

    @Override public void onDisable() { removeAll(); }

    // --- persistence: remember placed chests so a crash can't leave orphans behind
    private void persist() {
        List<String> out = new ArrayList<>();
        for (Active a : active.values()) out.add(key(a.loc));
        getConfig().set("_ACTIVE", out);
        saveConfig();
    }
    private void cleanupLeftovers() {
        for (String k : getConfig().getStringList("_ACTIVE")) {
            String[] p = k.split(";");
            World w = Bukkit.getWorld(p[0]);
            if (w == null) continue;
            Block b = w.getBlockAt(Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            if (b.getType() == Material.CHEST) { ((Chest) b.getState()).getBlockInventory().clear(); b.setType(Material.AIR); }
        }
        getConfig().set("_ACTIVE", new ArrayList<String>());
        saveConfig();
    }
    private void removeChest(Active a) {
        Block b = a.loc.getBlock();
        if (b.getType() == Material.CHEST) { ((Chest) b.getState()).getBlockInventory().clear(); b.setType(Material.AIR); }
    }
    private void removeAll() { for (Active a : active.values()) removeChest(a); active.clear(); persist(); }

    // --- spawning
    private String rollRarity() {
        int total = 0; Map<String, Integer> w = new LinkedHashMap<>();
        for (String t : TIERS) { int v = getConfig().getInt("RARITY-WEIGHTS." + t, 0); w.put(t, v); total += v; }
        if (total <= 0) return "BASIC";
        int r = rnd.nextInt(total);
        for (var e : w.entrySet()) { r -= e.getValue(); if (r < 0) return e.getKey(); }
        return "BASIC";
    }

    /** forcedRarity null = roll by weights. Returns false if skipped (cap reached / no world). */
    private boolean spawnOne(String forcedRarity) {
        if (active.size() >= getConfig().getInt("MAX-ACTIVE", 12) && forcedRarity == null) return false;
        List<String> worlds = getConfig().getStringList("WORLDS");
        if (worlds.isEmpty()) return false;
        World w = Bukkit.getWorld(worlds.get(rnd.nextInt(worlds.size())));
        if (w == null || w.getEnvironment() != World.Environment.NORMAL) return false;
        double min = getConfig().getDouble("MIN-DISTANCE-FROM-SPAWN", 150);
        double max = Math.min(getConfig().getDouble("MAX-DISTANCE-FROM-SPAWN", 2500), w.getWorldBorder().getSize() / 2 - 16);
        if (max <= min) return false;
        Location c = w.getWorldBorder().getCenter();
        double ang = rnd.nextDouble() * Math.PI * 2, dist = min + rnd.nextDouble() * (max - min);
        int x = (int) (c.getX() + Math.cos(ang) * dist), z = (int) (c.getZ() + Math.sin(ang) * dist);
        String rarity = forcedRarity != null ? forcedRarity : rollRarity();
        // async chunk load avoids a main-thread stall; continuation runs on the main thread.
        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> place(w, x, z, rarity));
        return true;
    }

    private void place(World w, int x, int z, String rarity) {
        int y = w.getHighestBlockYAt(x, z);
        Block ground = w.getBlockAt(x, y, z), at = ground.getRelative(0, 1, 0);
        Material gm = ground.getType();
        if (gm == Material.WATER || gm == Material.LAVA || ground.isLiquid() || gm.name().endsWith("LEAVES") || !at.getType().isAir()) return; // unlucky spot; next tick tries another
        at.setType(Material.CHEST);
        Chest chest = (Chest) at.getState();
        fill(chest.getBlockInventory(), rarity);
        long exp = System.currentTimeMillis() + 60_000L * getConfig().getInt("DESPAWN-MINUTES", 60);
        active.put(key(at.getLocation()), new Active(at.getLocation(), rarity, exp));
        persist();
        if (getConfig().getBoolean("ANNOUNCE", true)) {
            int prec = getConfig().getInt("ANNOUNCE-PRECISION", 100);
            int ax = prec > 0 ? Math.round(x / (float) prec) * prec : x, az = prec > 0 ? Math.round(z / (float) prec) * prec : z;
            Bukkit.broadcastMessage("§6[Loot] " + CHAT.get(rarity) + NAME.get(rarity) + " §6chest has appeared in §f" + w.getName() + " §6near X " + ax + ", Z " + az + (prec > 0 ? " (±" + prec / 2 + ")" : "") + "! Find it first!");
        }
    }

    private void fill(Inventory inv, String rarity) {
        List<String> entries = getConfig().getStringList("LOOT." + rarity);
        List<Integer> r = getConfig().getIntegerList("ROLLS." + rarity);
        int lo = r.size() > 0 ? r.get(0) : 2, hi = r.size() > 1 ? r.get(1) : 4;
        int rolls = lo + rnd.nextInt(Math.max(1, hi - lo + 1));
        int total = 0; List<String[]> parsed = new ArrayList<>();
        for (String s : entries) {
            String[] p = s.split(":"); if (p.length < 3) continue;
            if (Material.matchMaterial(p[0]) == null) { getLogger().warning("Bad loot material: " + p[0]); continue; }
            parsed.add(p); total += Integer.parseInt(p[2]);
        }
        if (total <= 0) return;
        for (int i = 0; i < rolls; i++) {
            int pick = rnd.nextInt(total); String[] chosen = parsed.get(0);
            for (String[] p : parsed) { pick -= Integer.parseInt(p[2]); if (pick < 0) { chosen = p; break; } }
            String[] range = chosen[1].split("-");
            int a = Integer.parseInt(range[0]), b = range.length > 1 ? Integer.parseInt(range[1]) : a;
            int amt = a + rnd.nextInt(Math.max(1, b - a + 1));
            ItemStack it = new ItemStack(Material.matchMaterial(chosen[0]), Math.min(amt, Material.matchMaterial(chosen[0]).getMaxStackSize()));
            int slot; int tries = 0; do { slot = rnd.nextInt(inv.getSize()); tries++; } while (inv.getItem(slot) != null && tries < 40);
            inv.setItem(slot, it);
        }
    }

    // --- upkeep: expiry + visual hint
    private void tick() {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Iterator<Active> it = active.values().iterator(); it.hasNext(); ) {
            Active a = it.next();
            if (a.expiresAt <= now || a.loc.getBlock().getType() != Material.CHEST) { removeChest(a); it.remove(); changed = true; continue; }
            if (getConfig().getBoolean("PARTICLES", true)) {
                Location top = a.loc.clone().add(0.5, 1.2, 0.5);
                boolean near = false;
                for (Player p : a.loc.getWorld().getPlayers()) if (p.getLocation().distanceSquared(a.loc) < 48 * 48) { near = true; break; }
                if (near) for (int i = 0; i < 12; i++)
                    a.loc.getWorld().spawnParticle(Particle.DUST, top.clone().add(0, i * 0.5, 0), 2, 0.1, 0.1, 0.1, 0, new Particle.DustOptions(COLOR.get(a.rarity), 1.6f));
            }
        }
        if (changed) persist();
    }

    // --- events
    @EventHandler public void onOpen(InventoryOpenEvent e) {
        if (!(e.getInventory().getHolder() instanceof Chest ch) || !(e.getPlayer() instanceof Player p)) return;
        Active a = active.get(key(ch.getLocation()));
        if (a != null && a.expiresAt != -1)
            Bukkit.broadcastMessage("§6[Loot] §f" + p.getName() + " §6found a " + CHAT.get(a.rarity) + NAME.get(a.rarity) + " §6loot chest!");
    }
    @EventHandler public void onClose(InventoryCloseEvent e) {
        if (!(e.getInventory().getHolder() instanceof Chest ch)) return;
        Active a = active.get(key(ch.getLocation()));
        if (a == null) return;
        boolean empty = Arrays.stream(e.getInventory().getContents()).allMatch(i -> i == null || i.getType().isAir());
        if (empty) { a.loc.getBlock().setType(Material.AIR); active.remove(key(a.loc)); persist(); }
    }
    @EventHandler public void onBreak(org.bukkit.event.block.BlockBreakEvent e) {
        Active a = active.get(key(e.getBlock().getLocation()));
        if (a != null) { // can't be mined for the chest item / dupe; open it instead
            e.setCancelled(true); e.getPlayer().sendMessage("§cOpen the loot chest instead of breaking it.");
        }
    }
    @EventHandler public void onExplode(org.bukkit.event.entity.EntityExplodeEvent e) { e.blockList().removeIf(b -> active.containsKey(key(b.getLocation()))); }
    @EventHandler public void onHopper(InventoryMoveItemEvent e) {
        if (e.getSource().getHolder() instanceof Chest ch && active.containsKey(key(ch.getLocation()))) e.setCancelled(true);
    }

    // --- command
    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        String sub = a.length == 0 ? "list" : a[0].toLowerCase();
        switch (sub) {
            case "spawn" -> {
                String r = a.length > 1 ? a[1].toUpperCase() : null;
                if (r != null && !TIERS.contains(r)) { s.sendMessage("Rarity: " + TIERS); return true; }
                s.sendMessage(spawnOne(r) ? "Spawning a chest..." : "Could not spawn (cap/world/no valid spot).");
            }
            case "clear" -> { int n = active.size(); removeAll(); s.sendMessage("Removed " + n + " chests."); }
            case "reload" -> { reloadConfig(); s.sendMessage("Reloaded."); }
            default -> {
                s.sendMessage(active.size() + " active loot chests:");
                for (Active x : active.values()) s.sendMessage(" " + x.rarity + " @ " + key(x.loc));
            }
        }
        return true;
    }
}
