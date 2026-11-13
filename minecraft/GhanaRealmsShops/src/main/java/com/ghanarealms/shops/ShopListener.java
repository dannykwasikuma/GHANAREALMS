package com.ghanarealms.shops;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShopListener implements Listener {

    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "(?:B(\\d+(?:\\.\\d+)?))?\\s*/?\\s*(?:S(\\d+(?:\\.\\d+)?))?", Pattern.CASE_INSENSITIVE);
    private static final BlockFace[] SIDES = {BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.DOWN};

    private final JavaPlugin plugin;
    private final ShopStore store;
    private final Economy economy;

    public ShopListener(JavaPlugin plugin, ShopStore store, Economy economy) {
        this.plugin = plugin;
        this.store = store;
        this.economy = economy;
    }

    @EventHandler
    public void onSignChange(SignChangeEvent event) {
        String trigger = plugin.getConfig().getString("GENERAL.TRIGGER-LINE", "[shop]");
        if (!event.getLine(0).equalsIgnoreCase(trigger)) return;

        Player player = event.getPlayer();
        Block chestBlock = findAdjacentChest(event.getBlock());
        if (chestBlock == null) {
            player.sendMessage(color(plugin.getConfig().getString("MESSAGES.NO-CHEST-FOUND")));
            event.setLine(0, "");
            return;
        }

        int quantity;
        try {
            quantity = Integer.parseInt(event.getLine(1).trim());
        } catch (NumberFormatException e) {
            quantity = 1;
        }

        Matcher m = PRICE_PATTERN.matcher(event.getLine(2).trim());
        double buyPrice = -1, sellPrice = -1;
        if (m.matches()) {
            if (m.group(1) != null) buyPrice = Double.parseDouble(m.group(1));
            if (m.group(2) != null) sellPrice = Double.parseDouble(m.group(2));
        }
        if (buyPrice < 0 && sellPrice < 0) {
            player.sendMessage(color(plugin.getConfig().getString("MESSAGES.INVALID-FORMAT")));
            event.setLine(0, "");
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();
        Material material = held.getType() == Material.AIR ? Material.STONE : held.getType();

        ShopStore.Shop shop = new ShopStore.Shop();
        shop.owner = player.getUniqueId();
        shop.ownerName = player.getName();
        shop.material = material;
        shop.quantity = Math.max(1, quantity);
        shop.buyPrice = buyPrice;
        shop.sellPrice = sellPrice;
        shop.chestLocation = chestBlock.getLocation();
        store.put(shop);

        event.setLine(0, "\u00A76" + player.getName());
        event.setLine(1, "\u00A7f" + shop.quantity + "x " + prettyName(material));
        event.setLine(2, formatPriceLine(shop));
        event.setLine(3, buyPrice >= 0 ? "\u00A7aBuy" : (sellPrice >= 0 ? "\u00A7bSell only" : ""));

        player.sendMessage(color(plugin.getConfig().getString("MESSAGES.SHOP-CREATED", "")
                .replace("{amount}", String.valueOf(shop.quantity))
                .replace("{item}", prettyName(material))
                .replace("{buy}", buyPrice >= 0 ? "\u20B5" + buyPrice : "off")
                .replace("{sell}", sellPrice >= 0 ? "\u20B5" + sellPrice : "off")));
    }

    private Block findAdjacentChest(Block signBlock) {
        for (BlockFace face : SIDES) {
            Block rel = signBlock.getRelative(face);
            if (rel.getState() instanceof Chest) return rel;
        }
        return null;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null || !(clicked.getState() instanceof org.bukkit.block.Sign)) return;

        Block chestBlock = findAdjacentChest(clicked);
        if (chestBlock == null) return;
        ShopStore.Shop shop = store.get(chestBlock.getLocation());
        if (shop == null) return;

        event.setCancelled(true);
        Player player = event.getPlayer();
        if (player.getUniqueId().equals(shop.owner)) {
            player.sendMessage(color(plugin.getConfig().getString("MESSAGES.CANNOT-BUY-OWN-SHOP")));
            return;
        }

        if (player.isSneaking()) {
            sellToShop(player, shop, chestBlock);
        } else {
            buyFromShop(player, shop, chestBlock);
        }
    }

    private void buyFromShop(Player buyer, ShopStore.Shop shop, Block chestBlock) {
        if (shop.buyPrice < 0) {
            buyer.sendMessage(color(plugin.getConfig().getString("MESSAGES.BUY-DISABLED")));
            return;
        }
        Chest chest = (Chest) chestBlock.getState();
        ItemStack stockItem = new ItemStack(shop.material, shop.quantity);
        if (!chest.getInventory().containsAtLeast(stockItem, shop.quantity)) {
            buyer.sendMessage(color(plugin.getConfig().getString("MESSAGES.SHOP-OUT-OF-STOCK")));
            return;
        }
        if (economy == null || !economy.has(buyer, shop.buyPrice)) {
            buyer.sendMessage(color(plugin.getConfig().getString("MESSAGES.BUYER-INSUFFICIENT-FUNDS")));
            return;
        }
        chest.getInventory().removeItem(stockItem);
        var leftover = buyer.getInventory().addItem(stockItem);
        leftover.values().forEach(item -> buyer.getWorld().dropItemNaturally(buyer.getLocation(), item));

        economy.withdrawPlayer(buyer, shop.buyPrice);
        economy.depositPlayer(Bukkit.getOfflinePlayer(shop.owner), shop.buyPrice);

        buyer.sendMessage(color(plugin.getConfig().getString("MESSAGES.BUY-SUCCESS", "")
                .replace("{amount}", String.valueOf(shop.quantity)).replace("{item}", prettyName(shop.material))
                .replace("{price}", String.valueOf(shop.buyPrice))));
    }

    private void sellToShop(Player seller, ShopStore.Shop shop, Block chestBlock) {
        if (shop.sellPrice < 0) {
            seller.sendMessage(color(plugin.getConfig().getString("MESSAGES.SELL-DISABLED")));
            return;
        }
        ItemStack stockItem = new ItemStack(shop.material, shop.quantity);
        if (!seller.getInventory().containsAtLeast(stockItem, shop.quantity)) {
            seller.sendMessage(color(plugin.getConfig().getString("MESSAGES.YOU-DONT-HAVE-ITEMS")));
            return;
        }
        Chest chest = (Chest) chestBlock.getState();
        if (economy == null || !economy.has(Bukkit.getOfflinePlayer(shop.owner), shop.sellPrice)) {
            seller.sendMessage(color(plugin.getConfig().getString("MESSAGES.OWNER-INSUFFICIENT-FUNDS")));
            return;
        }

        seller.getInventory().removeItem(stockItem);
        var leftover = chest.getInventory().addItem(stockItem);
        if (!leftover.isEmpty()) {
            // shouldn't normally happen given the capacity check above, but if the
            // chest filled between the check and now, give the items back rather
            // than losing them
            leftover.values().forEach(item -> seller.getInventory().addItem(item));
        }

        economy.withdrawPlayer(Bukkit.getOfflinePlayer(shop.owner), shop.sellPrice);
        economy.depositPlayer(seller, shop.sellPrice);

        seller.sendMessage(color(plugin.getConfig().getString("MESSAGES.SELL-SUCCESS", "")
                .replace("{amount}", String.valueOf(shop.quantity)).replace("{item}", prettyName(shop.material))
                .replace("{price}", String.valueOf(shop.sellPrice))));
    }

    private String formatPriceLine(ShopStore.Shop shop) {
        StringBuilder sb = new StringBuilder("\u00A7e");
        if (shop.buyPrice >= 0) sb.append("B").append(shop.buyPrice);
        if (shop.buyPrice >= 0 && shop.sellPrice >= 0) sb.append("/");
        if (shop.sellPrice >= 0) sb.append("S").append(shop.sellPrice);
        return sb.toString();
    }

    private String prettyName(Material m) {
        String[] parts = m.name().toLowerCase().split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(" ");
        return sb.toString().trim();
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }
}
