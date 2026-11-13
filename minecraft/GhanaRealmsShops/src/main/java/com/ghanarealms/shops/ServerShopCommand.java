package com.ghanarealms.shops;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** /servershop create <category> | remove | list | reload | open <category> [player] */
public class ServerShopCommand implements TabExecutor {

    private final JavaPlugin plugin;
    private final ServerShops shops;
    private final ShopStore playerShops;
    private final ServerShopListener menus;

    public ServerShopCommand(JavaPlugin plugin, ServerShops shops, ShopStore playerShops, ServerShopListener menus) {
        this.plugin = plugin;
        this.shops = shops;
        this.playerShops = playerShops;
        this.menus = menus;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("ghanarealmsshops.admin")) {
            sender.sendMessage(color("&cYou do not have permission."));
            return true;
        }
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(sender, args);
            case "remove" -> remove(sender);
            case "list" -> list(sender);
            case "reload" -> {
                shops.reload();
                sender.sendMessage(color("&aServer shops reloaded."));
            }
            case "open" -> open(sender, args);
            default -> usage(sender);
        }
        return true;
    }

    private void usage(CommandSender s) {
        s.sendMessage(color("&6/servershop create <category|hub> &7- turn the chest you look at into a shop"));
        s.sendMessage(color("&6/servershop remove &7- un-register the chest you look at"));
        s.sendMessage(color("&6/servershop list &7- categories and shop chests"));
        s.sendMessage(color("&6/servershop open <category|hub> [player] &7- open a menu directly"));
        s.sendMessage(color("&6/servershop reload &7- reload server-shops.yml"));
    }

    private Block targetContainer(Player p) {
        Block b = p.getTargetBlockExact(6);
        if (b == null) return null;
        Material t = b.getType();
        return (t == Material.CHEST || t == Material.TRAPPED_CHEST || t == Material.BARREL) ? b : null;
    }

    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return;
        }
        if (args.length < 2) {
            usage(sender);
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        if (!shops.exists(id)) {
            sender.sendMessage(color("&cNo category '" + id + "'. Known: hub, " + String.join(", ", categoryIds())));
            return;
        }
        Block b = targetContainer(p);
        if (b == null) {
            sender.sendMessage(color("&cLook at a chest, trapped chest or barrel (within 6 blocks)."));
            return;
        }
        if (playerShops.get(b.getLocation()) != null) {
            sender.sendMessage(color("&cThat chest is already a player sign shop."));
            return;
        }
        if (b.getState() instanceof Container c && !c.getInventory().isEmpty()) {
            sender.sendMessage(color("&cEmpty the chest first - its contents would be locked away."));
            return;
        }
        shops.bind(b, id);
        sender.sendMessage(color("&aThis chest is now the &f" + id + " &ashop. Players right-click it to browse."));
    }

    private void remove(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return;
        }
        Block b = targetContainer(p);
        if (b == null || shops.categoryAt(b) == null) {
            sender.sendMessage(color("&cLook at a registered server shop chest."));
            return;
        }
        shops.unbind(b);
        sender.sendMessage(color("&eServer shop removed."));
    }

    private void list(CommandSender sender) {
        sender.sendMessage(color("&6Categories: &f" + (shops.categories().isEmpty() ? "(none)" : String.join(", ", categoryIds()))));
        sender.sendMessage(color("&6Shop chests: &f" + shops.chests().size()));
        for (Map.Entry<String, String> e : shops.chests().entrySet()) {
            sender.sendMessage(color("&7 " + e.getValue() + " @ " + e.getKey()));
        }
    }

    private void open(CommandSender sender, String[] args) {
        if (args.length < 2) {
            usage(sender);
            return;
        }
        Player target = null;
        if (args.length >= 3) target = Bukkit.getPlayerExact(args[2]);
        else if (sender instanceof Player p) target = p;
        if (target == null) {
            sender.sendMessage(color("&cName an online player."));
            return;
        }
        if (!shops.exists(args[1])) {
            sender.sendMessage(color("&cNo category '" + args[1] + "'."));
            return;
        }
        menus.open(target, args[1], 0);
    }

    private List<String> categoryIds() {
        List<String> ids = new ArrayList<>();
        for (ServerShops.Category c : shops.categories()) ids.add(c.id);
        return ids;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("ghanarealmsshops.admin")) return List.of();
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("create", "remove", "list", "reload", "open")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("create") || args[0].equalsIgnoreCase("open"))) {
            List<String> ids = new ArrayList<>(categoryIds());
            ids.add(ServerShops.HUB);
            for (String s : ids) if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
        }
        return out;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
