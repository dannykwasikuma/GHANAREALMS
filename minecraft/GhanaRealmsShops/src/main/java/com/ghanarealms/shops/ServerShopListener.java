package com.ghanarealms.shops;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Right-click a registered server-shop chest and a buy/sell menu opens instead of the chest.
 * Menu clicks: left = buy 1, shift+left = buy a stack, right = sell 1, shift+right = sell a stack.
 */
public class ServerShopListener implements Listener {

    private static final int ITEMS_PER_PAGE = 45;
    private static final int[] HUB_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};

    private final JavaPlugin plugin;
    private final ServerShops shops;
    private final Economy economy;

    public ServerShopListener(JavaPlugin plugin, ServerShops shops, Economy economy) {
        this.plugin = plugin;
        this.shops = shops;
        this.economy = economy;
    }

    // ---------------------------------------------------------------- opening

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getClickedBlock();
        if (block == null) return;
        Material type = block.getType();
        if (type != Material.CHEST && type != Material.TRAPPED_CHEST && type != Material.BARREL) return;

        String categoryId = shops.categoryAt(block);
        if (categoryId == null) return;

        // Never let the real chest open - the shop menu replaces it. Both hands fire this event.
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        if (!player.hasPermission("ghanarealmsshops.use")) {
            player.sendMessage(color(msg("NO-PERMISSION", "&cYou cannot use shops.")));
            return;
        }
        if (!categoryId.equals(ServerShops.HUB) && shops.category(categoryId) == null) {
            player.sendMessage(color(msg("SHOP-NOT-CONFIGURED", "&cThis shop is not stocked right now.")));
            return;
        }
        open(player, categoryId, 0);
    }

    public void open(Player player, String categoryId, int page) {
        ShopMenuHolder holder;
        Inventory inv;

        if (categoryId.equalsIgnoreCase(ServerShops.HUB)) {
            holder = new ShopMenuHolder(ServerShops.HUB, 0);
            inv = Bukkit.createInventory(holder, 54, color(msg("HUB-TITLE", "&6&lGhanaRealms Market")));
            holder.setInventory(inv);
            fill(inv);
            int i = 0;
            for (ServerShops.Category c : shops.categories()) {
                if (i >= HUB_SLOTS.length) break;
                int slot = HUB_SLOTS[i++];
                inv.setItem(slot, named(c.icon, c.title, List.of("&7" + c.entries.size() + " items", "&eClick to browse")));
                holder.categorySlots.put(slot, c.id);
            }
            holder.balanceSlot = 49;
            inv.setItem(49, balanceItem(player));
        } else {
            ServerShops.Category cat = shops.category(categoryId);
            if (cat == null) return;
            int pages = Math.max(1, (cat.entries.size() + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE);
            page = Math.max(0, Math.min(page, pages - 1));

            holder = new ShopMenuHolder(cat.id, page);
            String title = cat.title + (pages > 1 ? " &8(" + (page + 1) + "/" + pages + ")" : "");
            inv = Bukkit.createInventory(holder, 54, color(title));
            holder.setInventory(inv);
            fill(inv);

            int start = page * ITEMS_PER_PAGE;
            for (int i = 0; i < ITEMS_PER_PAGE && start + i < cat.entries.size(); i++) {
                ServerShops.Entry e = cat.entries.get(start + i);
                inv.setItem(i, entryIcon(e));
                holder.entrySlots.put(i, e);
            }
            if (page > 0) {
                holder.prevSlot = 45;
                inv.setItem(45, named(Material.ARROW, "&ePrevious page", List.of()));
            }
            if (page < pages - 1) {
                holder.nextSlot = 53;
                inv.setItem(53, named(Material.ARROW, "&eNext page", List.of()));
            }
            if (hubAvailable()) {
                holder.backSlot = 48;
                inv.setItem(48, named(Material.COMPASS, "&eAll categories", List.of()));
            }
            holder.balanceSlot = 49;
            inv.setItem(49, balanceItem(player));
        }
        player.openInventory(inv);
    }

    private boolean hubAvailable() {
        return shops.categories().size() > 1;
    }

    // ---------------------------------------------------------------- menu clicks

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof ShopMenuHolder holder)) return;
        event.setCancelled(true); // nothing may be moved in or out of a shop menu
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() == null || event.getClickedInventory() != top) return;

        int slot = event.getRawSlot();

        if (slot == holder.prevSlot) { open(player, holder.categoryId, holder.page - 1); return; }
        if (slot == holder.nextSlot) { open(player, holder.categoryId, holder.page + 1); return; }
        if (slot == holder.backSlot) { open(player, ServerShops.HUB, 0); return; }

        String hubTarget = holder.categorySlots.get(slot);
        if (hubTarget != null) { open(player, hubTarget, 0); return; }

        ServerShops.Entry entry = holder.entrySlots.get(slot);
        if (entry == null) return;

        ClickType click = event.getClick();
        switch (click) {
            case LEFT -> buy(player, entry, false);
            case SHIFT_LEFT -> buy(player, entry, true);
            case RIGHT -> sell(player, entry, false);
            case SHIFT_RIGHT -> sell(player, entry, true);
            default -> { /* number keys, drops, double-click etc. do nothing */ }
        }
        if (holder.balanceSlot >= 0) top.setItem(holder.balanceSlot, balanceItem(player));
        player.updateInventory();
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShopMenuHolder) event.setCancelled(true);
    }

    private void buy(Player player, ServerShops.Entry entry, boolean stack) {
        if (entry.buy < 0) {
            player.sendMessage(color(msg("NOT-FOR-SALE", "&cThis item is not for sale.")));
            return;
        }
        if (economy == null) {
            player.sendMessage(color(msg("NO-ECONOMY", "&cThe shop economy is unavailable right now.")));
            return;
        }
        int qty = stack ? entry.material.getMaxStackSize() : 1;
        double cost = entry.buy * qty;
        if (!economy.has(player, cost)) {
            player.sendMessage(color(msg("CANNOT-AFFORD", "&cYou need &6\u20B5{price}&c for that.").replace("{price}", money(cost))));
            return;
        }
        EconomyResponse charged = economy.withdrawPlayer(player, cost);
        if (!charged.transactionSuccess()) {
            player.sendMessage(color(msg("CANNOT-AFFORD", "&cYou need &6\u20B5{price}&c for that.").replace("{price}", money(cost))));
            return;
        }

        Map<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(entry.material, qty));
        int notGiven = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
        if (notGiven > 0) {
            economy.depositPlayer(player, entry.buy * notGiven); // refund what did not fit
        }
        int given = qty - notGiven;
        if (given <= 0) {
            player.sendMessage(color(msg("INVENTORY-FULL", "&cYour inventory is full.")));
            return;
        }
        player.sendMessage(color(msg("BOUGHT", "&aBought &f{amount}x {item} &afor &6\u20B5{price}&a.")
                .replace("{amount}", String.valueOf(given))
                .replace("{item}", pretty(entry.material))
                .replace("{price}", money(entry.buy * given))));
    }

    private void sell(Player player, ServerShops.Entry entry, boolean stack) {
        if (entry.sell < 0) {
            player.sendMessage(color(msg("NOT-BOUGHT", "&cThe shop does not buy this item.")));
            return;
        }
        if (economy == null) {
            player.sendMessage(color(msg("NO-ECONOMY", "&cThe shop economy is unavailable right now.")));
            return;
        }
        int have = countPlain(player, entry.material);
        if (have <= 0) {
            player.sendMessage(color(msg("YOU-DONT-HAVE", "&cYou do not have any plain {item} to sell.")
                    .replace("{item}", pretty(entry.material))));
            return;
        }
        int qty = stack ? Math.min(have, entry.material.getMaxStackSize()) : 1;
        removePlain(player, entry.material, qty);
        economy.depositPlayer(player, entry.sell * qty);
        player.sendMessage(color(msg("SOLD", "&aSold &f{amount}x {item} &afor &6\u20B5{price}&a.")
                .replace("{amount}", String.valueOf(qty))
                .replace("{item}", pretty(entry.material))
                .replace("{price}", money(entry.sell * qty))));
    }

    /** Only counts untouched items - renamed/enchanted/custom gear is never sold as a plain item. */
    private int countPlain(Player player, Material material) {
        int total = 0;
        for (ItemStack it : player.getInventory().getStorageContents()) {
            if (it != null && it.getType() == material && !it.hasItemMeta()) total += it.getAmount();
        }
        return total;
    }

    private void removePlain(Player player, Material material, int amount) {
        ItemStack[] contents = player.getInventory().getStorageContents();
        for (int i = 0; i < contents.length && amount > 0; i++) {
            ItemStack it = contents[i];
            if (it == null || it.getType() != material || it.hasItemMeta()) continue;
            int take = Math.min(amount, it.getAmount());
            amount -= take;
            if (take >= it.getAmount()) contents[i] = null; else it.setAmount(it.getAmount() - take);
        }
        player.getInventory().setStorageContents(contents);
    }

    // ---------------------------------------------------------------- protection

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (shops.categoryAt(block) == null) return;
        Player player = event.getPlayer();
        if (!player.hasPermission("ghanarealmsshops.admin")) {
            event.setCancelled(true);
            player.sendMessage(color(msg("SHOP-PROTECTED", "&cThis shop chest is protected.")));
            return;
        }
        shops.unbind(block);
        player.sendMessage(color(msg("SHOP-REMOVED", "&eServer shop removed.")));
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> shops.categoryAt(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> shops.categoryAt(b) != null);
    }

    // ---------------------------------------------------------------- item helpers

    private ItemStack entryIcon(ServerShops.Entry e) {
        List<String> lore = new ArrayList<>();
        int stack = e.material.getMaxStackSize();
        lore.add(e.buy >= 0
                ? "&7Buy: &a\u20B5" + money(e.buy) + " &8(left 1 / shift " + stack + ")"
                : "&8Not for sale");
        lore.add(e.sell >= 0
                ? "&7Sell: &b\u20B5" + money(e.sell) + " &8(right 1 / shift " + stack + ")"
                : "&8Shop does not buy this");
        return named(e.material, "&f" + pretty(e.material), lore);
    }

    private ItemStack balanceItem(Player player) {
        String bal = economy == null ? "n/a" : "\u20B5" + money(economy.getBalance(player));
        return named(Material.GOLD_INGOT, "&6Your balance: &f" + bal, List.of("&7Sell plain items with right-click."));
    }

    private void fill(Inventory inv) {
        ItemStack pane = named(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int i = 45; i < 54; i++) inv.setItem(i, pane);
    }

    private ItemStack named(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            List<String> colored = new ArrayList<>();
            for (String l : lore) colored.add(color(l));
            meta.setLore(colored);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String money(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private String pretty(Material m) {
        String[] parts = m.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    private String msg(String key, String def) {
        return plugin.getConfig().getString("SERVER-SHOP.MESSAGES." + key, def);
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
