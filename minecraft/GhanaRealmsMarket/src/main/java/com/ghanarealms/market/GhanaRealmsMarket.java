package com.ghanarealms.market;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.*;
import org.bukkit.block.Sign;
import org.bukkit.block.data.*;
import org.bukkit.block.data.type.*;
import org.bukkit.block.sign.Side;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.player.*;
import java.io.File;
import java.io.IOException;
import org.bukkit.event.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Procedurally builds the GhanaRealms marketplace (3x3 grid of districts) centred on the admin's
 * position and turns "[GShop]" signs into shop openers (/gshop <category>). Build on FLAT land
 * (or a test world) - it clears everything above the floor in its footprint. Back up first.
 */
public class GhanaRealmsMarket extends JavaPlugin implements CommandExecutor, Listener {
    static final int PLOT = 45, ROAD = 7, GRID = 3, SPAN = GRID * PLOT + (GRID - 1) * ROAD + 2 * ROAD; // outer margin of one road width

    // ---- build queue (lazy, so a 150x150 build doesn't allocate hundreds of thousands of objects)
    private interface Op { /** @return blocks consumed from budget; < budget means finished */ int run(int budget); }
    private final Deque<Op> queue = new ArrayDeque<>();
    private World w;
    private boolean building;

    // ---- protected region + rentable plots (persisted in region.yml)
    private record Plot(int n, int x1, int z1, int x2, int z2, int signX, int signY, int signZ, UUID owner, String ownerName) {}
    private String regionWorld; private int rMinX, rMaxX, rMinZ, rMaxZ, rMinY, rMaxY, floorY;
    private final List<Plot> plots = new ArrayList<>();
    private final List<Plot> pendingPlots = new ArrayList<>();
    private NamespacedKey NPC;
    private final Map<UUID, Long> warned = new HashMap<>();

    private class Fill implements Op {
        final int x1, y1, z1, x2, y2, z2; final BlockData d; int x, y, z; boolean init;
        Fill(int a, int b, int c, int d2, int e, int f, BlockData data) { x1 = Math.min(a, d2); x2 = Math.max(a, d2); y1 = Math.min(b, e); y2 = Math.max(b, e); z1 = Math.min(c, f); z2 = Math.max(c, f); d = data; }
        public int run(int budget) {
            if (!init) { x = x1; y = y1; z = z1; init = true; }
            int used = 0;
            while (used < budget && y <= y2) {
                w.getBlockAt(x, y, z).setBlockData(d, false); used++;
                if (++x > x2) { x = x1; if (++z > z2) { z = z1; y++; } }
            }
            return y > y2 ? used : budget;
        }
    }
    private class One implements Op {
        final Runnable r; One(Runnable r) { this.r = r; }
        public int run(int budget) { r.run(); return 0; }
    }

    private void fill(int x1, int y1, int z1, int x2, int y2, int z2, Material m) { queue.add(new Fill(x1, y1, z1, x2, y2, z2, m.createBlockData())); }
    private void set(int x, int y, int z, Material m) { queue.add(new One(() -> w.getBlockAt(x, y, z).setBlockData(m.createBlockData(), false))); }
    private void setData(int x, int y, int z, BlockData d) { queue.add(new One(() -> w.getBlockAt(x, y, z).setBlockData(d, false))); }

    private void sign(int x, int y, int z, BlockFace facing, String... lines) {
        queue.add(new One(() -> {
            Block b = w.getBlockAt(x, y, z);
            b.setType(Material.OAK_SIGN, false);
            BlockData bd = b.getBlockData();
            if (bd instanceof Rotatable r) { r.setRotation(facing); b.setBlockData(r, false); }
            if (b.getState() instanceof Sign s) {
                for (int i = 0; i < 4; i++) s.getSide(Side.FRONT).line(i, Component.text(i < lines.length ? lines[i] : ""));
                s.setWaxed(true); s.update(true, false);
            }
        }));
    }

    /** Wall sign attached to the solid block behind it (so it can't pop off). facing = direction the text faces. */
    private void wallSign(int x, int y, int z, BlockFace facing, String... lines) {
        queue.add(new One(() -> {
            Block b = w.getBlockAt(x, y, z);
            b.setType(Material.OAK_WALL_SIGN, false);
            BlockData bd = b.getBlockData();
            if (bd instanceof Directional dd) { dd.setFacing(facing); b.setBlockData(dd, false); }
            if (b.getState() instanceof Sign s) {
                for (int i = 0; i < 4; i++) s.getSide(Side.FRONT).line(i, Component.text(i < lines.length ? lines[i] : ""));
                s.setWaxed(true); s.update(true, false);
            }
        }));
    }

    // ---- palette
    static final Material[] KENTE = {Material.RED_TERRACOTTA, Material.YELLOW_TERRACOTTA, Material.GREEN_TERRACOTTA, Material.BLACK_TERRACOTTA};

    private record Stall(String title, String category) {}
    private record District(String name, Material floor, Material trim, List<Stall> stalls) {}

    private static List<Stall> st(String cat, String... names) { List<Stall> l = new ArrayList<>(); for (String n : names) l.add(new Stall(n, cat)); return l; }
    private static List<Stall> cat(String[][] pairs) { List<Stall> l = new ArrayList<>(); for (String[] p : pairs) l.add(new Stall(p[0], p[1])); return l; }

    // grid positions [row][col], north (row 0) to south, west (col 0) to east
    private List<District> districts() {
        District[] g = new District[9];
        g[0] = new District("Okaishie Electronics & Equipment", Material.SMOOTH_STONE, Material.IRON_BLOCK, cat(new String[][]{
            {"Miner's Tools", "tool"}, {"Pickaxe Palace", "tool"}, {"Fishing & Farming", "tool"}, {"Explorer Gear", "rare"}, {"Fireworks & Flight", "rare"},
            {"Compass & Maps", "rare"}, {"Mining Equipment", "tool"}, {"Quick Fix Repairs", "tool"}, {"Accra Gadgets", "rare"}, {"Lantern & Torch", "building"}}));
        g[1] = new District("Kumasi Armour & Weapons District", Material.POLISHED_ANDESITE, Material.CHISELED_STONE_BRICKS, cat(new String[][]{
            {"Kumasi Armour House", "armour"}, {"Volta Mail Guard", "armour"}, {"Ashanti Blades", "weapon"}, {"Golden Stool Exclusives", "weapon"}, {"Savanna Archers", "weapon"},
            {"Adinkra Shields", "weapon"}, {"Netherite Forge", "armour"}, {"Diamond Defenders", "armour"}, {"Warrior Bundles", "bundle"}, {"Special Combat", "weapon"}}));
        g[2] = new District("GhanaRealms Premium & Luxury", Material.POLISHED_BLACKSTONE, Material.GOLD_BLOCK, cat(new String[][]{
            {"The Enchantment House", "enchantment"}, {"Premium Collection", "enchantment"}, {"The Elytra Centre", "rare"}, {"Gold Coast Luxury", "rare"}, {"Mending Masters", "enchantment"},
            {"Armour Enchants", "enchantment"}, {"Weapon Enchants", "enchantment"}, {"Mining Enchants", "enchantment"}, {"Premium Bundles", "bundle"}, {"Rare Wings", "rare"}}));
        g[3] = new District("Kejetia Food & Grocery Market", Material.COARSE_DIRT, Material.JUNGLE_PLANKS, cat(new String[][]{
            {"Jollof Corner", "food"}, {"Waakye Joint", "food"}, {"Fufu & Soup", "food"}, {"Kelewele Corner", "food"}, {"Bofrot Stand", "food"},
            {"Fresh Produce", "food"}, {"Fish Market", "food"}, {"Butcher & Goat", "food"}, {"Food Baskets", "bundle"}, {"Street Food", "food"}}));
        g[4] = null; // centre = main marketplace plaza
        g[5] = new District("Tema Building Materials Market", Material.GRAVEL, Material.STONE_BRICKS, cat(new String[][]{
            {"Lumber Yard", "building"}, {"Stone & Brick", "building"}, {"Glass & Windows", "building"}, {"Concrete & Cement", "building"}, {"Sand & Gravel", "building"},
            {"Kente Colours", "building"}, {"Terracotta", "building"}, {"Lights & Doors", "building"}, {"Wool & Dye", "building"}, {"Iron & Bars", "building"}}));
        g[6] = new District("Gold Coast Special Items & Trading Centre", Material.SMOOTH_SANDSTONE, Material.CUT_SANDSTONE, cat(new String[][]{
            {"Gold Coast Treasures", "rare"}, {"Ashanti Trading Centre", "bundle"}, {"Potion Corner", "potion"}, {"Brewing Supplies", "potion"}, {"Fighter Potions", "potion"},
            {"Rare Finds", "rare"}, {"Ender Pearls", "rare"}, {"Shulker Boxes", "rare"}, {"Totems & Trinkets", "rare"}, {"Travel Bundles", "bundle"}}));
        g[8] = new District("Player-Owned Business District", Material.STONE_BRICKS, Material.OAK_PLANKS, List.of());
        return Arrays.asList(g);
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        NPC = new NamespacedKey(this, "npc");
        loadRegion();
        getCommand("gmarket").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("In-game only."); return true; }
        if (a.length == 0 || a[0].equalsIgnoreCase("plan")) {
            Location o = p.getLocation(); int half = SPAN / 2;
            s.sendMessage("§6[Market] Will build a " + SPAN + "x" + SPAN + " area centred on you: X " + (o.getBlockX() - half) + ".." + (o.getBlockX() + half)
                + ", Z " + (o.getBlockZ() - half) + ".." + (o.getBlockZ() + half) + ", floor at Y " + (o.getBlockY() - 1) + ", clearing 16 blocks above it.");
            s.sendMessage("§6[Market] It REPLACES everything in that area. Use flat land or a test world and back the world up. Run §e/gmarket build confirm§6 to proceed.");
            return true;
        }
        if (a[0].equalsIgnoreCase("build")) {
            if (building) { s.sendMessage("A build is already running."); return true; }
            if (a.length < 2 || !a[1].equalsIgnoreCase("confirm")) { s.sendMessage("Run /gmarket plan first, then /gmarket build confirm."); return true; }
            Location o = p.getLocation();
            w = o.getWorld(); building = true;
            try { buildAll(o.getBlockX(), o.getBlockY() - 1, o.getBlockZ()); } catch (Throwable t) { building = false; queue.clear(); s.sendMessage("Build planning failed: " + t); return true; }
            s.sendMessage("§6[Market] Building... " + queue.size() + " operations queued. You'll be told when it's done.");
            final int per = getConfig().getInt("BLOCKS-PER-TICK", 6000);
            new BukkitRunnable() {
                @Override public void run() {
                    int budget = per;
                    while (budget > 0 && !queue.isEmpty()) {
                        Op op = queue.peek(); int used = op.run(budget);
                        if (used < budget) queue.poll(); // finished that op
                        budget -= Math.max(used, 1);
                    }
                    if (queue.isEmpty()) { building = false; cancel(); plots.clear(); plots.addAll(pendingPlots); saveRegion(); p.sendMessage("§a[Market] The GhanaRealms Marketplace is built and PROTECTED. Akwaaba!"); }
                }
            }.runTaskTimer(this, 1L, 1L);
            return true;
        }
        if (a[0].equalsIgnoreCase("removenpcs")) { removeNpcs(); s.sendMessage("Removed market NPCs."); return true; }
        if (a[0].equalsIgnoreCase("plot")) return plotCommand(s, a);
        return false;
    }

    private boolean plotCommand(CommandSender s, String[] a) {
        if (a.length < 2 || a[1].equalsIgnoreCase("list")) {
            if (plots.isEmpty()) s.sendMessage("No plots (build the market first).");
            for (Plot pl : plots) s.sendMessage("Plot " + pl.n + ": " + (pl.owner == null ? "FOR RENT" : pl.ownerName));
            return true;
        }
        int n; try { n = Integer.parseInt(a[2]); } catch (Exception e) { s.sendMessage("/gmarket plot <assign <n> <player>|release <n>|list>"); return true; }
        Plot cur = plots.stream().filter(q -> q.n == n).findFirst().orElse(null);
        if (cur == null) { s.sendMessage("No such plot."); return true; }
        Plot upd;
        if (a[1].equalsIgnoreCase("assign") && a.length >= 4) {
            OfflinePlayer op = Bukkit.getOfflinePlayer(a[3]);
            upd = new Plot(cur.n, cur.x1, cur.z1, cur.x2, cur.z2, cur.signX, cur.signY, cur.signZ, op.getUniqueId(), a[3]);
        } else if (a[1].equalsIgnoreCase("release")) {
            upd = new Plot(cur.n, cur.x1, cur.z1, cur.x2, cur.z2, cur.signX, cur.signY, cur.signZ, null, null);
        } else { s.sendMessage("/gmarket plot <assign <n> <player>|release <n>|list>"); return true; }
        plots.set(plots.indexOf(cur), upd); saveRegion();
        World wd = Bukkit.getWorld(regionWorld);
        if (wd != null && wd.getBlockAt(upd.signX, upd.signY, upd.signZ).getState() instanceof Sign sg) {
            sg.setWaxed(false);
            sg.getSide(Side.FRONT).line(1, Component.text(upd.owner == null ? "FOR RENT" : "OWNED BY"));
            sg.getSide(Side.FRONT).line(2, Component.text(upd.owner == null ? "Ask staff" : upd.ownerName));
            sg.setWaxed(true); sg.update(true, false);
        }
        s.sendMessage("Plot " + n + (upd.owner == null ? " released." : " assigned to " + upd.ownerName + "."));
        return true;
    }

    // ================================================================ region protection
    private void loadRegion() {
        File f = new File(getDataFolder(), "region.yml"); if (!f.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        regionWorld = y.getString("world"); rMinX = y.getInt("minX"); rMaxX = y.getInt("maxX"); rMinZ = y.getInt("minZ"); rMaxZ = y.getInt("maxZ");
        rMinY = y.getInt("minY"); rMaxY = y.getInt("maxY"); floorY = y.getInt("floorY");
        plots.clear();
        for (Map<?, ?> m : y.getMapList("plots")) {
            Object ow = m.get("owner");
            plots.add(new Plot((Integer) m.get("n"), (Integer) m.get("x1"), (Integer) m.get("z1"), (Integer) m.get("x2"), (Integer) m.get("z2"),
                (Integer) m.get("sx"), (Integer) m.get("sy"), (Integer) m.get("sz"), ow == null ? null : UUID.fromString(ow.toString()), (String) m.get("ownerName")));
        }
    }
    private void saveRegion() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("world", regionWorld); y.set("minX", rMinX); y.set("maxX", rMaxX); y.set("minZ", rMinZ); y.set("maxZ", rMaxZ);
        y.set("minY", rMinY); y.set("maxY", rMaxY); y.set("floorY", floorY);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Plot p : plots) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("n", p.n); m.put("x1", p.x1); m.put("z1", p.z1); m.put("x2", p.x2); m.put("z2", p.z2);
            m.put("sx", p.signX); m.put("sy", p.signY); m.put("sz", p.signZ); m.put("owner", p.owner == null ? null : p.owner.toString()); m.put("ownerName", p.ownerName);
            out.add(m);
        }
        y.set("plots", out);
        try { getDataFolder().mkdirs(); y.save(new File(getDataFolder(), "region.yml")); } catch (IOException e) { getLogger().severe("Could not save region.yml: " + e); }
    }
    private boolean inRegion(Location l) {
        return regionWorld != null && l.getWorld() != null && l.getWorld().getName().equals(regionWorld)
            && l.getBlockX() >= rMinX && l.getBlockX() <= rMaxX && l.getBlockZ() >= rMinZ && l.getBlockZ() <= rMaxZ && l.getBlockY() >= rMinY - 1 && l.getBlockY() <= rMaxY + 8;
    }
    private boolean canBuild(Player p, Location l) {
        if (!inRegion(l)) return true;
        if (p != null && p.hasPermission("ghanarealmsmarket.bypass")) return true;
        if (p != null && l.getBlockY() > floorY) {
            for (Plot pl : plots) if (p.getUniqueId().equals(pl.owner) && l.getBlockX() >= pl.x1 && l.getBlockX() <= pl.x2 && l.getBlockZ() >= pl.z1 && l.getBlockZ() <= pl.z2) return true;
        }
        if (p != null) { long now = System.currentTimeMillis(); if (now - warned.getOrDefault(p.getUniqueId(), 0L) > 3000) { warned.put(p.getUniqueId(), now); p.sendMessage("§cThe marketplace is protected. Rent a plot in the Player-Owned Business District to build."); } }
        return false;
    }
    @EventHandler(ignoreCancelled = true) public void onBreak(BlockBreakEvent e) { if (!canBuild(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onPlace(BlockPlaceEvent e) { if (!canBuild(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onBucket(PlayerBucketEmptyEvent e) { if (!canBuild(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onBucketFill(PlayerBucketFillEvent e) { if (!canBuild(e.getPlayer(), e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onExplode(EntityExplodeEvent e) { e.blockList().removeIf(b -> inRegion(b.getLocation())); }
    @EventHandler(ignoreCancelled = true) public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(b -> inRegion(b.getLocation())); }
    @EventHandler(ignoreCancelled = true) public void onBurn(BlockBurnEvent e) { if (inRegion(e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onIgnite(BlockIgniteEvent e) { if (inRegion(e.getBlock().getLocation()) && !(e.getPlayer() != null && canBuild(e.getPlayer(), e.getBlock().getLocation()))) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onFlow(BlockFromToEvent e) { if (inRegion(e.getToBlock().getLocation()) && !inRegion(e.getBlock().getLocation())) e.setCancelled(true); } // outside liquids can't flood in
    @EventHandler(ignoreCancelled = true) public void onEntityChange(EntityChangeBlockEvent e) { if (inRegion(e.getBlock().getLocation())) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onHanging(HangingBreakByEntityEvent e) { if (inRegion(e.getEntity().getLocation()) && !(e.getRemover() instanceof Player p && canBuild(p, e.getEntity().getLocation()))) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true) public void onSpawn(CreatureSpawnEvent e) {
        if (e.getEntity() instanceof Monster && inRegion(e.getLocation()) && e.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) e.setCancelled(true); // no mobs in the market (raids/apocalypse use CUSTOM spawns)
    }
    @EventHandler public void onTrample(PlayerInteractEvent e) { if (e.getAction() == Action.PHYSICAL && e.getClickedBlock() != null && inRegion(e.getClickedBlock().getLocation())) e.setCancelled(true); }

    // ================================================================ NPC click
    @EventHandler public void onNpcClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        String cat = e.getRightClicked().getPersistentDataContainer().get(NPC, PersistentDataType.STRING);
        if (cat == null) return;
        e.setCancelled(true);
        if (!cat.isEmpty() && e.getPlayer().hasPermission("ghanarealmsmarket.use")) Bukkit.dispatchCommand(e.getPlayer(), "gshop " + cat);
    }
    @EventHandler(ignoreCancelled = true) public void onNpcHurt(EntityDamageEvent e) { if (e.getEntity().getPersistentDataContainer().has(NPC, PersistentDataType.STRING)) e.setCancelled(true); }

    // ================================================================ layout
    private void buildAll(int cx, int fy, int cz) {
        int half = SPAN / 2, x0 = cx - half, z0 = cz - half;
        // 1) terrain: sample the footprint so hills are cut down and hollows/water filled (not just a flat pad)
        int ymin = fy, ymax = fy;
        for (int sx = x0; sx < x0 + SPAN; sx += 4) for (int sz = z0; sz < z0 + SPAN; sz += 4) {
            int hy = w.getHighestBlockYAt(sx, sz); ymin = Math.min(ymin, hy); ymax = Math.max(ymax, hy);
        }
        int top = Math.min(w.getMaxHeight() - 1, Math.max(fy + 16, ymax + 4));
        int bottom = Math.max(w.getMinHeight(), Math.min(fy - 3, ymin - 2));
        bottom = Math.max(bottom, fy - 48);
        fill(x0, fy + 1, z0, x0 + SPAN - 1, top, z0 + SPAN - 1, Material.AIR);                 // hills, trees, water above the floor
        fill(x0, bottom, z0, x0 + SPAN - 1, fy - 1, z0 + SPAN - 1, Material.DIRT);              // hollows, ravines, shallows
        fill(x0, fy, z0, x0 + SPAN - 1, fy, z0 + SPAN - 1, Material.COARSE_DIRT);              // roads / margins (dusty laterite)
        // remember the protected region; plots are collected while building
        regionWorld = w.getName(); rMinX = x0; rMaxX = x0 + SPAN - 1; rMinZ = z0; rMaxZ = z0 + SPAN - 1; rMinY = fy; rMaxY = top; floorY = fy;
        pendingPlots.clear();
        queue.add(new One(this::removeNpcs));                                                   // rebuilding: drop old shop NPCs first
        // 2) districts
        List<District> ds = districts();
        for (int r = 0; r < GRID; r++) for (int c = 0; c < GRID; c++) {
            int px = x0 + ROAD + c * (PLOT + ROAD), pz = z0 + ROAD + r * (PLOT + ROAD), idx = r * GRID + c;
            if (idx == 4) { plaza(px, fy, pz); continue; }
            if (idx == 7) { mall(px, fy, pz); continue; }
            District d = ds.get(idx);
            if (idx == 8) { businessDistrict(px, fy, pz, d); continue; }
            stallDistrict(px, fy, pz, d, r);
        }
        // 3) lamp posts along the roads
        for (int i = 0; i <= GRID; i++) for (int j = 0; j <= GRID; j++) {
            int lx = x0 + ROAD / 2 + i * (PLOT + ROAD), lz = z0 + ROAD / 2 + j * (PLOT + ROAD);
            if (lx < x0 + SPAN && lz < z0 + SPAN) lamp(lx, fy + 1, lz);
        }
    }

    private void lamp(int x, int y, int z) { fill(x, y, z, x, y + 3, z, Material.OAK_FENCE); set(x, y + 4, z, Material.LANTERN); }

    private void stallDistrict(int px, int fy, int pz, District d, int row) {
        fill(px, fy, pz, px + PLOT - 1, fy, pz + PLOT - 1, d.floor);
        // border
        fill(px, fy, pz, px + PLOT - 1, fy, pz, d.trim); fill(px, fy, pz + PLOT - 1, px + PLOT - 1, fy, pz + PLOT - 1, d.trim);
        fill(px, fy, pz, px, fy, pz + PLOT - 1, d.trim); fill(px + PLOT - 1, fy, pz, px + PLOT - 1, fy, pz + PLOT - 1, d.trim);
        gateway(px + PLOT / 2 - 3, fy, pz, d);
        // two rows of 5 stalls (7 wide, 5 deep, 1 gap), customers walk the central aisle at z offset 17..26
        for (int i = 0; i < 10; i++) {
            int sx = px + 2 + (i % 5) * 8, sz = (i < 5) ? pz + 7 : pz + PLOT - 7 - 5;
            boolean frontSouth = i < 5; // north row faces south; south row faces north
            Stall sd = i < d.stalls.size() ? d.stalls.get(i) : new Stall("Stall " + (i + 1), null);
            stall(sx, fy, sz, i, sd, frontSouth);
        }
    }

    /** Kente-striped market stall: 4 posts, striped terracotta roof, counter at the aisle side, clickable sign. */
    private void stall(int sx, int fy, int sz, int idx, Stall sd, boolean frontSouth) {
        int w7 = 7, d5 = 5, h = 4;
        for (int cx : new int[]{sx, sx + w7 - 1}) for (int cz : new int[]{sz, sz + d5 - 1}) fill(cx, fy + 1, cz, cx, fy + h, cz, Material.STRIPPED_JUNGLE_LOG);
        // roof stripes run along X in kente order; slight front overhang
        for (int dx = 0; dx < w7; dx++) fill(sx + dx, fy + h + 1, sz - 1, sx + dx, fy + h + 1, sz + d5, KENTE[(dx + idx) % 4]);
        // back wall (planks, half height) so stalls read as booths
        int backZ = frontSouth ? sz : sz + d5 - 1;
        fill(sx, fy + 1, backZ, sx + w7 - 1, fy + 2, backZ, Material.JUNGLE_PLANKS);
        fill(sx, fy + 1, sz, sx, fy + 2, sz + d5 - 1, Material.JUNGLE_FENCE); fill(sx + w7 - 1, fy + 1, sz, sx + w7 - 1, fy + 2, sz + d5 - 1, Material.JUNGLE_FENCE);
        fill(sx + 1, fy, sz + 1, sx + w7 - 2, fy, sz + d5 - 2, Material.SMOOTH_SANDSTONE);
        // counter at front
        int frontZ = frontSouth ? sz + d5 - 1 : sz;
        fill(sx + 1, fy + 1, frontZ, sx + w7 - 2, fy + 1, frontZ, Material.BARREL);
        { Lantern lan = (Lantern) Material.LANTERN.createBlockData(); lan.setHanging(true); setData(sx + w7 / 2, fy + h, frontSouth ? sz + 2 : sz + d5 - 3, lan); }
        BlockFace face = frontSouth ? BlockFace.SOUTH : BlockFace.NORTH;
        String[] t = wrapTitle(sd.title);
        if (sd.category != null) sign(sx + w7 / 2, fy + 2, frontZ, face, "[GShop]", sd.category, t[0], t[1]);
        else sign(sx + w7 / 2, fy + 2, frontZ, face, "", t[0], t[1], "");
        npc(sx + w7 / 2 + 0.5, fy + 1, (frontSouth ? frontZ - 1 : frontZ + 1) + 0.5, frontSouth ? 0f : 180f, sd);
    }

    private static Villager.Profession profession(String cat) {
        if (cat == null) return Villager.Profession.NITWIT;
        return switch (cat) {
            case "armour" -> Villager.Profession.ARMORER; case "weapon" -> Villager.Profession.WEAPONSMITH; case "tool" -> Villager.Profession.TOOLSMITH;
            case "food" -> Villager.Profession.FARMER; case "enchantment" -> Villager.Profession.LIBRARIAN; case "potion" -> Villager.Profession.CLERIC;
            case "building" -> Villager.Profession.MASON; case "rare" -> Villager.Profession.CARTOGRAPHER; case "bundle" -> Villager.Profession.LEATHERWORKER;
            case "cosmetic" -> Villager.Profession.SHEPHERD; default -> Villager.Profession.NITWIT;
        };
    }
    /** Shopkeeper NPC: a frozen, invulnerable villager; right-click opens the shop for its category. */
    private void npc(double x, double y, double z, float yaw, Stall sd) {
        queue.add(new One(() -> {
            Location l = new Location(w, x, y, z, yaw, 0);
            Villager v = (Villager) w.spawnEntity(l, EntityType.VILLAGER);
            v.setAI(false); v.setInvulnerable(true); v.setSilent(true); v.setPersistent(true); v.setRemoveWhenFarAway(false);
            v.setProfession(profession(sd.category)); v.setCollidable(false);
            v.customName(Component.text(sd.title).color(NamedTextColor.GOLD)); v.setCustomNameVisible(true);
            v.getPersistentDataContainer().set(NPC, PersistentDataType.STRING, sd.category == null ? "" : sd.category);
        }));
    }
    private void removeNpcs() {
        for (World wd : Bukkit.getWorlds()) for (Villager v : wd.getEntitiesByClass(Villager.class)) if (v.getPersistentDataContainer().has(NPC, PersistentDataType.STRING)) v.remove();
    }

    private String[] wrapTitle(String s) {
        if (s.length() <= 15) return new String[]{s, ""};
        int cut = s.lastIndexOf(' ', 15); if (cut < 0) cut = 15;
        return new String[]{s.substring(0, cut), s.substring(cut).trim()};
    }

    private void gateway(int gx, int fy, int gz, District d) {
        fill(gx, fy + 1, gz, gx, fy + 6, gz, d.trim); fill(gx + 6, fy + 1, gz, gx + 6, fy + 6, gz, d.trim);
        fill(gx, fy + 7, gz, gx + 6, fy + 7, gz, Material.GOLD_BLOCK);
        for (int i = 0; i < 7; i++) set(gx + i, fy + 6, gz, KENTE[i % 4]);
        wallSign(gx + 3, fy + 7, gz - 1, BlockFace.NORTH, "AKWAABA", wrapTitle(d.name)[0], wrapTitle(d.name)[1], "");
    }

    // ---- main marketplace: Black Star plaza + flags + arch
    private static final String[] STAR = {
        "......X......", ".....XXX.....", ".....XXX.....", "XXXXXXXXXXXXX", ".XXXXXXXXXXX.", "..XXXXXXXXX..", "...XXXXXXX...",
        "...XXXXXXX...", "..XXXX.XXXX..", "..XXX...XXX..", ".XX.......XX.", ".X.........X."};
    private void plaza(int px, int fy, int pz) {
        fill(px, fy, pz, px + PLOT - 1, fy, pz + PLOT - 1, Material.SMOOTH_SANDSTONE);
        fill(px, fy, pz, px + PLOT - 1, fy, pz, Material.RED_TERRACOTTA); fill(px, fy, pz + PLOT - 1, px + PLOT - 1, fy, pz + PLOT - 1, Material.GREEN_TERRACOTTA);
        fill(px, fy, pz, px, fy, pz + PLOT - 1, Material.YELLOW_TERRACOTTA); fill(px + PLOT - 1, fy, pz, px + PLOT - 1, fy, pz + PLOT - 1, Material.YELLOW_TERRACOTTA);
        int sx = px + PLOT / 2 - 6, sz = pz + PLOT / 2 - 6;
        fill(sx - 2, fy, sz - 2, sx + 14, fy, sz + 15, Material.POLISHED_BLACKSTONE);
        for (int r = 0; r < STAR.length; r++) for (int c = 0; c < 13; c++) if (STAR[r].charAt(c) == 'X') set(sx + c, fy, sz + r, Material.GOLD_BLOCK);
        sign(px + PLOT / 2, fy + 1, pz + PLOT / 2 + 8, BlockFace.SOUTH, "GHANAREALMS", "MARKETPLACE", "Akwaaba!", "Type /gshop");
        // independence arch at the north entrance
        int ax = px + PLOT / 2 - 7;
        fill(ax, fy + 1, pz + 2, ax, fy + 10, pz + 2, Material.SMOOTH_SANDSTONE); fill(ax + 14, fy + 1, pz + 2, ax + 14, fy + 10, pz + 2, Material.SMOOTH_SANDSTONE);
        fill(ax, fy + 11, pz + 2, ax + 14, fy + 11, pz + 2, Material.GOLD_BLOCK);
        wallSign(ax + 7, fy + 11, pz + 1, BlockFace.NORTH, "AKWAABA", "Welcome to", "GhanaRealms", "Freedom & Justice");
        // three flags (red / gold / green stripes, black star centre) on poles
        for (int i = 0; i < 3; i++) flag(px + 8 + i * 14, fy, pz + PLOT - 8);
        // fountain
        fill(px + PLOT / 2 - 2, fy, pz + 12, px + PLOT / 2 + 2, fy + 1, pz + 16, Material.STONE_BRICKS);
        fill(px + PLOT / 2 - 1, fy + 1, pz + 13, px + PLOT / 2 + 1, fy + 1, pz + 15, Material.WATER);
    }
    private void flag(int x, int fy, int z) {
        fill(x, fy + 1, z, x, fy + 14, z, Material.OAK_FENCE);
        set(x, fy + 15, z, Material.GOLD_BLOCK);
        int fx = x + 1;
        fill(fx, fy + 11, z, fx + 8, fy + 14, z, Material.RED_WOOL);
        fill(fx, fy + 7, z, fx + 8, fy + 10, z, Material.YELLOW_WOOL);
        fill(fx, fy + 3, z, fx + 8, fy + 6, z, Material.GREEN_WOOL);
        set(fx + 4, fy + 9, z, Material.BLACK_WOOL); set(fx + 4, fy + 8, z, Material.BLACK_WOOL); set(fx + 3, fy + 9, z, Material.BLACK_WOOL); set(fx + 5, fy + 9, z, Material.BLACK_WOOL);
    }

    // ---- shopping mall: single building with 10 clickable shop fronts inside
    private void mall(int px, int fy, int pz) {
        int w2 = 39, d2 = 31, mx = px + 3, mz = pz + 7, hh = 9;
        fill(px, fy, pz, px + PLOT - 1, fy, pz + PLOT - 1, Material.SMOOTH_SANDSTONE);
        fill(mx, fy, mz, mx + w2 - 1, fy, mz + d2 - 1, Material.SMOOTH_QUARTZ);
        fill(mx, fy + 1, mz, mx + w2 - 1, fy + hh, mz + d2 - 1, Material.WHITE_CONCRETE);
        fill(mx + 1, fy + 1, mz + 1, mx + w2 - 2, fy + hh - 1, mz + d2 - 2, Material.AIR);
        fill(mx, fy + hh + 1, mz, mx + w2 - 1, fy + hh + 1, mz + d2 - 1, Material.YELLOW_CONCRETE); // roof
        for (int i = 0; i < w2; i += 3) fill(mx + i, fy + hh, mz, mx + Math.min(i + 1, w2 - 1), fy + hh, mz, KENTE[(i / 3) % 4]);
        // glass storefront band + wide entrance on the north side
        fill(mx + 2, fy + 2, mz, mx + w2 - 3, fy + 5, mz, Material.GLASS);
        fill(mx + w2 / 2 - 3, fy + 1, mz, mx + w2 / 2 + 3, fy + 6, mz, Material.AIR);
        wallSign(mx + w2 / 2, fy + 7, mz - 1, BlockFace.NORTH, "GHANAREALMS", "SHOPPING MALL", "Accra Mall Ghana", "");
        // interior: shop fronts along the two long walls, atrium in between
        Stall[] fronts = {new Stall("Mall Bundles", "bundle"), new Stall("Fashion House", "cosmetic"), new Stall("Armour Hub", "armour"), new Stall("Blade Boutique", "weapon"),
            new Stall("Gadgets", "tool"), new Stall("Food Court", "food"), new Stall("Potion Pharmacy", "potion"), new Stall("Enchant Studio", "enchantment"),
            new Stall("Home & Build", "building"), new Stall("Rare Gallery", "rare")};
        for (int i = 0; i < 10; i++) {
            int sx = mx + 3 + (i % 5) * 7, west = i < 5; int sz = west ? mz + 2 : mz + d2 - 4;
            fill(sx, fy + 1, sz, sx + 4, fy + 1, sz, Material.BARREL);
            fill(sx, fy + 2, sz, sx + 4, fy + 3, sz, Material.AIR);
            fill(sx, fy + 2, sz, sx, fy + 3, sz, Material.JUNGLE_FENCE); fill(sx + 4, fy + 2, sz, sx + 4, fy + 3, sz, Material.JUNGLE_FENCE);
            fill(sx, fy + 4, sz, sx + 4, fy + 4, sz, KENTE[i % 4]);
            String[] t = wrapTitle(fronts[i].title);
            BlockFace face = west ? BlockFace.SOUTH : BlockFace.NORTH;
            if (fronts[i].category != null) sign(sx + 2, fy + 2, sz, face, "[GShop]", fronts[i].category, t[0], t[1]);
            else sign(sx + 2, fy + 2, sz, face, "", t[0], t[1], "");
            npc(sx + 2.5, fy + 1, (west ? sz - 1 : sz + 1) + 0.5, west ? 0f : 180f, fronts[i]);
        }
        fill(mx + w2 / 2 - 2, fy + 1, mz + d2 / 2 - 2, mx + w2 / 2 + 2, fy + 1, mz + d2 / 2 + 2, Material.STONE_BRICKS);
        fill(mx + w2 / 2 - 1, fy + 1, mz + d2 / 2 - 1, mx + w2 / 2 + 1, fy + 1, mz + d2 / 2 + 1, Material.WATER);
        for (int i = 4; i < w2; i += 8) { set(mx + i, fy + hh - 1, mz + d2 / 2, Material.LANTERN); }
        // gold star above the entrance
        set(mx + w2 / 2, fy + 9, mz, Material.GOLD_BLOCK);
    }

    // ---- player-owned business district: 12 fenced 9x9 plots to rent/claim
    private void businessDistrict(int px, int fy, int pz, District d) {
        fill(px, fy, pz, px + PLOT - 1, fy, pz + PLOT - 1, d.floor);
        gateway(px + PLOT / 2 - 3, fy, pz, d);
        int n = 1;
        for (int r = 0; r < 3; r++) for (int c = 0; c < 4; c++) {
            int x = px + 2 + c * 11, z = pz + 8 + r * 12;
            fill(x, fy, z, x + 8, fy, z + 8, Material.SMOOTH_STONE);
            for (int i = 0; i < 9; i++) for (int[] e : new int[][]{{i, 0}, {i, 8}, {0, i}, {8, i}}) set(x + e[0], fy + 1, z + e[1], Material.OAK_FENCE);
            set(x + 4, fy + 1, z + 8, Material.OAK_FENCE_GATE);
            sign(x + 5, fy + 1, z + 9, BlockFace.SOUTH, "Plot " + n, "FOR RENT", "Ask staff", "Sign shops OK");
            pendingPlots.add(new Plot(n, x, z, x + 8, z + 8, x + 5, fy + 1, z + 9, null, null)); n++;
        }
    }

    // ================================================================ clicking [GShop] signs
    @EventHandler public void onSignClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null || e.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        if (!(e.getClickedBlock().getState() instanceof Sign s)) return;
        String l0 = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(s.getSide(Side.FRONT).line(0));
        if (!l0.equals("[GShop]")) return;
        String category = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(s.getSide(Side.FRONT).line(1)).trim();
        e.setCancelled(true);
        if (!e.getPlayer().hasPermission("ghanarealmsmarket.use")) return;
        Bukkit.dispatchCommand(e.getPlayer(), "gshop " + category);
    }
}
