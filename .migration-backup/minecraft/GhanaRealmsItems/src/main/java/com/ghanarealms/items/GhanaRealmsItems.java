package com.ghanarealms.items;

import com.google.gson.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.*;
import java.util.*;

public class GhanaRealmsItems extends JavaPlugin implements CommandExecutor, Listener {
    private static final List<String> CAT_ORDER = List.of("bundle", "kit", "armour", "weapon", "tool", "rare", "utility", "enchantment", "food", "potion", "resource");
    private static final Map<String, String> CAT_TITLE = Map.ofEntries(
        Map.entry("bundle", "Bundles"), Map.entry("kit", "Kits"), Map.entry("armour", "Kumasi Armour House"), Map.entry("weapon", "Weapons & Combat"),
        Map.entry("tool", "Tools & Mining"), Map.entry("rare", "Elytra & Rare Items"), Map.entry("utility", "Utilities"),
        Map.entry("enchantment", "Enchantment House"), Map.entry("food", "Kejetia Food Market"), Map.entry("potion", "Potions & Brewing"),
        Map.entry("resource", "Resource Packs"));
    private static final Map<String, Material> CAT_ICON = Map.ofEntries(
        Map.entry("bundle", Material.CHEST), Map.entry("kit", Material.BARREL), Map.entry("armour", Material.DIAMOND_CHESTPLATE), Map.entry("weapon", Material.DIAMOND_SWORD),
        Map.entry("tool", Material.DIAMOND_PICKAXE), Map.entry("rare", Material.ELYTRA), Map.entry("utility", Material.ENDER_CHEST),
        Map.entry("enchantment", Material.ENCHANTED_BOOK), Map.entry("food", Material.COOKED_CHICKEN), Map.entry("potion", Material.POTION),
        Map.entry("resource", Material.DIAMOND));
    private static final Map<String, String> RARITY = Map.of("BASIC", "§7Fugu", "UNCOMMON", "§aKente", "RARE", "§bAdinkra", "EPIC", "§dAsante Gold", "LEGENDARY", "§6Golden Stool");

    private record Prod(String slug, String name, String desc, String category, String rarity, long price, List<String> specs,
                        Material icon, Integer stock, Integer limit, Integer window) {}

    private static class Holder implements InventoryHolder {
        String category; int page; Inventory inv;
        @Override public Inventory getInventory() { return inv; }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private volatile List<Prod> catalogue = List.of();
    private Economy econ;
    private final Map<UUID, String> pendingConfirm = new HashMap<>();
    private final Map<UUID, Long> pendingAt = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp != null) econ = rsp.getProvider(); else getLogger().warning("No Vault economy found - /gshop purchases are disabled.");
        getCommand("gritem").setExecutor(this); getCommand("gshop").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        long ticks = 20L * Math.max(30, getConfig().getInt("BRIDGE.SYNC-INTERVAL-SECONDS", 300));
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::refresh, 40L, ticks);
    }

    // ---------------- bridge
    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create(getConfig().getString("BRIDGE.STORE-BASE-URL", "") + path))
            .header("x-bridge-secret", getConfig().getString("BRIDGE.BRIDGE-SECRET", "")).header("content-type", "application/json");
    }
    private void refresh() {
        if (!getConfig().getBoolean("BRIDGE.ENABLED", true)) return;
        try {
            HttpResponse<String> r = http.send(req("/api/bridge/catalogue").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) { getLogger().warning("Catalogue sync HTTP " + r.statusCode()); return; }
            List<Prod> out = new ArrayList<>();
            for (JsonElement e : JsonParser.parseString(r.body()).getAsJsonObject().getAsJsonArray("products")) {
                JsonObject o = e.getAsJsonObject();
                List<String> specs = new ArrayList<>();
                o.getAsJsonArray("item_specs").forEach(s -> specs.add(s.getAsString()));
                Material icon = o.has("icon_material") && !o.get("icon_material").isJsonNull() ? Material.matchMaterial(o.get("icon_material").getAsString()) : null;
                if (icon == null) icon = Material.PAPER;
                out.add(new Prod(o.get("slug").getAsString(), o.get("name").getAsString(), o.get("description").getAsString(), o.get("category").getAsString(),
                    o.get("rarity").isJsonNull() ? "BASIC" : o.get("rarity").getAsString(), o.get("price").getAsLong(), specs, icon,
                    o.get("stock").isJsonNull() ? null : o.get("stock").getAsInt(),
                    o.get("purchase_limit_per_player").isJsonNull() ? null : o.get("purchase_limit_per_player").getAsInt(),
                    o.get("limit_window_days").isJsonNull() ? null : o.get("limit_window_days").getAsInt()));
            }
            catalogue = out;
        } catch (Exception ex) { getLogger().warning("Catalogue sync failed: " + ex); }
    }

    // ---------------- commands
    @Override public boolean onCommand(CommandSender s, Command c, String l, String[] a) {
        if (c.getName().equals("gritem")) {
            if (a.length < 3 || !a[0].equalsIgnoreCase("give")) { s.sendMessage("/gritem give <player> <MATERIAL> <amount> [name=..] [ench=..] [potion=..]"); return true; }
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t == null) { s.sendMessage("Player " + a[1] + " is not online."); return true; } // delivery system retries
            try { giveTo(t, ItemSpec.parse(String.join(" ", Arrays.copyOfRange(a, 2, a.length)))); s.sendMessage("Gave item to " + t.getName()); }
            catch (IllegalArgumentException ex) { s.sendMessage("Bad item spec: " + ex.getMessage()); getLogger().warning("Bad item spec: " + ex.getMessage()); }
            return true;
        }
        if (!(s instanceof Player p)) { s.sendMessage("Players only."); return true; }
        if (a.length > 0 && a[0].equalsIgnoreCase("cosmetic")) {
            String base = getConfig().getString("BRIDGE.STORE-BASE-URL", "");
            p.sendMessage(Component.text("Adinkra Cosmetics & Fashion", NamedTextColor.GOLD));
            p.sendMessage(Component.text("Chat titles are bought with GH₵ on the website, then delivered here automatically. Use /adinkra to wear them.", NamedTextColor.GRAY));
            p.sendMessage(Component.text("» " + base + "/store", NamedTextColor.AQUA).clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl(base + "/store")));
            return true;
        }
        if (a.length > 0 && CAT_TITLE.containsKey(a[0].toLowerCase())) openCategory(p, a[0].toLowerCase(), 0); else openMenu(p);
        return true;
    }

    private void giveTo(Player p, ItemStack it) {
        p.getInventory().addItem(it).values().forEach(left -> p.getWorld().dropItemNaturally(p.getLocation(), left));
    }

    // ---------------- GUI
    private void openMenu(Player p) {
        Holder h = new Holder(); h.category = null;
        h.inv = Bukkit.createInventory(h, 27, Component.text("GhanaRealms Market"));
        Set<String> have = new HashSet<>(); for (Prod x : catalogue) have.add(x.category);
        int slot = 10;
        for (String cat : CAT_ORDER) {
            if (!have.contains(cat)) continue;
            ItemStack ic = new ItemStack(CAT_ICON.getOrDefault(cat, Material.CHEST));
            ItemMeta m = ic.getItemMeta();
            m.displayName(Component.text(CAT_TITLE.getOrDefault(cat, cat)).color(NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
            m.getPersistentDataContainer().set(new NamespacedKeyHolder(this).key, org.bukkit.persistence.PersistentDataType.STRING, cat);
            ic.setItemMeta(m); h.inv.setItem(slot++, ic);
            if (slot == 17) slot = 19;
        }
        if (catalogue.isEmpty()) p.sendMessage("§cThe market is still loading - try again in a moment.");
        p.openInventory(h.inv);
    }
    private static final class NamespacedKeyHolder { final org.bukkit.NamespacedKey key; NamespacedKeyHolder(JavaPlugin pl) { key = new org.bukkit.NamespacedKey(pl, "cat"); } }

    private void openCategory(Player p, String cat, int page) {
        List<Prod> list = new ArrayList<>(); for (Prod x : catalogue) if (x.category.equals(cat)) list.add(x);
        int pages = Math.max(1, (list.size() + 44) / 45); page = Math.max(0, Math.min(page, pages - 1));
        Holder h = new Holder(); h.category = cat; h.page = page;
        h.inv = Bukkit.createInventory(h, 54, Component.text(CAT_TITLE.getOrDefault(cat, cat) + " (" + (page + 1) + "/" + pages + ")"));
        String cur = getConfig().getString("SHOP.CURRENCY-NAME", "coins");
        for (int i = 0; i < 45 && page * 45 + i < list.size(); i++) {
            Prod x = list.get(page * 45 + i);
            ItemStack ic = new ItemStack(x.icon); ItemMeta m = ic.getItemMeta();
            m.displayName(Component.text(x.name).color(NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(Component.text(RARITY.getOrDefault(x.rarity, "§7Fugu")));
            for (String line : wrap(x.desc, 40)) lore.add(Component.text("§7" + line));
            lore.add(Component.text("§6" + String.format("%,d", x.price) + " " + cur));
            if (x.stock != null) lore.add(Component.text(x.stock == 0 ? "§cSold out" : "§e" + x.stock + " left"));
            if (x.limit != null) lore.add(Component.text("§8Limit " + x.limit + " per player" + (x.window != null ? " / " + x.window + " days" : "")));
            lore.add(Component.text("§aClick twice to buy"));
            m.lore(lore);
            m.getPersistentDataContainer().set(new NamespacedKeyHolder(this).key, org.bukkit.persistence.PersistentDataType.STRING, "prod:" + x.slug);
            ic.setItemMeta(m); h.inv.setItem(i, ic);
        }
        h.inv.setItem(45, nav("« Back", Material.ARROW, "back")); 
        if (page > 0) h.inv.setItem(48, nav("Previous", Material.PAPER, "prev"));
        if (page < pages - 1) h.inv.setItem(50, nav("Next", Material.PAPER, "next"));
        p.openInventory(h.inv);
    }
    private ItemStack nav(String name, Material m, String tag) {
        ItemStack i = new ItemStack(m); ItemMeta mm = i.getItemMeta(); mm.displayName(Component.text(name).decoration(TextDecoration.ITALIC, false));
        mm.getPersistentDataContainer().set(new NamespacedKeyHolder(this).key, org.bukkit.persistence.PersistentDataType.STRING, "nav:" + tag);
        i.setItemMeta(mm); return i;
    }
    private static List<String> wrap(String s, int w) {
        List<String> out = new ArrayList<>(); StringBuilder cur = new StringBuilder();
        for (String word : s.split(" ")) { if (cur.length() + word.length() > w) { out.add(cur.toString()); cur.setLength(0); } cur.append(cur.length() > 0 ? " " : "").append(word); }
        if (cur.length() > 0) out.add(cur.toString()); return out;
    }

    @EventHandler public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h) || !(e.getWhoClicked() instanceof Player p)) return;
        e.setCancelled(true);
        ItemStack it = e.getCurrentItem(); if (it == null || !it.hasItemMeta()) return;
        String tag = it.getItemMeta().getPersistentDataContainer().get(new NamespacedKeyHolder(this).key, org.bukkit.persistence.PersistentDataType.STRING);
        if (tag == null) return;
        if (h.category == null) { openCategory(p, tag, 0); return; }
        if (tag.equals("nav:back")) { openMenu(p); return; }
        if (tag.equals("nav:prev")) { openCategory(p, h.category, h.page - 1); return; }
        if (tag.equals("nav:next")) { openCategory(p, h.category, h.page + 1); return; }
        if (tag.startsWith("prod:")) tryBuy(p, tag.substring(5));
    }

    // ---------------- purchase: reserve on the store -> charge coins -> give items (refund reservation on any failure)
    private void tryBuy(Player p, String slug) {
        if (econ == null) { p.sendMessage("§cThe shop economy is unavailable."); return; }
        Prod x = catalogue.stream().filter(q -> q.slug.equals(slug)).findFirst().orElse(null);
        if (x == null) { p.sendMessage("§cThat item is no longer available."); return; }
        long window = 1000L * getConfig().getInt("SHOP.CONFIRM-SECONDS", 6);
        if (!slug.equals(pendingConfirm.get(p.getUniqueId())) || System.currentTimeMillis() - pendingAt.getOrDefault(p.getUniqueId(), 0L) > window) {
            pendingConfirm.put(p.getUniqueId(), slug); pendingAt.put(p.getUniqueId(), System.currentTimeMillis());
            p.sendMessage("§eClick " + x.name + " again to confirm: §6" + String.format("%,d", x.price) + " " + getConfig().getString("SHOP.CURRENCY-NAME", "coins")); return;
        }
        pendingConfirm.remove(p.getUniqueId());
        if (!busy.add(p.getUniqueId())) return;
        if (!econ.has(p, x.price)) { busy.remove(p.getUniqueId()); p.sendMessage("§cYou can't afford that."); return; }
        final List<ItemStack> stacks = new ArrayList<>();
        try { for (String spec : x.specs) stacks.add(ItemSpec.parse(spec)); }   // validate BEFORE reserving/charging
        catch (IllegalArgumentException ex) {
            busy.remove(p.getUniqueId());
            p.sendMessage("§cThis item can't be delivered on this server version - you were not charged."); getLogger().warning("Bad spec in " + slug + ": " + ex.getMessage()); return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            String purchaseId = null; String err = null;
            try {
                JsonObject body = new JsonObject();
                body.addProperty("minecraftUuid", p.getUniqueId().toString()); body.addProperty("minecraftUsername", p.getName());
                body.addProperty("slug", slug); body.addProperty("quantity", 1); body.addProperty("expectedUnitPrice", x.price);
                HttpResponse<String> r = http.send(req("/api/bridge/catalogue/purchase").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(), HttpResponse.BodyHandlers.ofString());
                JsonObject j = JsonParser.parseString(r.body()).getAsJsonObject();
                if (r.statusCode() == 200) purchaseId = j.get("purchaseId").getAsString();
                else err = j.has("error") ? j.get("error").getAsString() : "HTTP " + r.statusCode();
            } catch (Exception ex) { err = "store unreachable"; }
            final String pid = purchaseId, fe = err;
            Bukkit.getScheduler().runTask(this, () -> {
                try {
                    if (pid == null) { p.sendMessage("§cCould not buy: " + fe); if (fe != null && fe.equals("Price changed")) Bukkit.getScheduler().runTaskAsynchronously(this, this::refresh); return; }
                    EconomyResponse w = econ.withdrawPlayer(p, x.price);
                    if (!w.transactionSuccess()) { refund(pid); p.sendMessage("§cPayment failed: " + w.errorMessage); return; }
                    for (ItemStack st : stacks) giveTo(p, st);
                    p.sendMessage("§aPurchased §f" + x.name + "§a!");
                    Bukkit.getScheduler().runTaskAsynchronously(this, this::refresh);
                } finally { busy.remove(p.getUniqueId()); }
            });
        });
    }
    private void refund(String purchaseId) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                JsonObject b = new JsonObject(); b.addProperty("purchaseId", purchaseId);
                http.send(req("/api/bridge/catalogue/refund").POST(HttpRequest.BodyPublishers.ofString(b.toString())).build(), HttpResponse.BodyHandlers.ofString());
            } catch (Exception ex) { getLogger().warning("Reservation refund failed for " + purchaseId + ": " + ex); }
        });
    }
}
