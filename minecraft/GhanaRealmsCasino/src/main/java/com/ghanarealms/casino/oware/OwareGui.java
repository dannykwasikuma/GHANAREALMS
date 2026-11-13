package com.ghanarealms.casino.oware;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

/**
 * Renders an OwareBoard as a 9-slot-wide, 3-row chest GUI: opponent's row on
 * top, status info in the middle row, own row on bottom.
 * Each player gets their OWN inventory instance pointed at the same
 * OwareBoard object, so clicks in either translate back to the same game.
 */
public class OwareGui {

    public static final int SIZE = 27;

    public static Inventory render(OwareBoard board, boolean forPlayerA, String title) {
        Inventory inv = Bukkit.createInventory(null, SIZE, title);

        // Opponent's row (top, slots 0-5), shown right-to-left to mirror a real board
        for (int local = 0; local < 6; local++) {
            int boardIdx = forPlayerA ? (11 - local) : local;
            int seeds = board.getPits()[boardIdx];
            inv.setItem(local, pitItem(seeds, false));
        }

        // Status row (middle, slot 13 = score info)
        inv.setItem(13, statusItem(board, forPlayerA));

        // Own row (bottom, slots 18-23)
        for (int local = 0; local < 6; local++) {
            int boardIdx = forPlayerA ? local : (11 - local);
            int seeds = board.getPits()[boardIdx];
            inv.setItem(18 + local, pitItem(seeds, true));
        }

        return inv;
    }

    private static ItemStack pitItem(int seeds, boolean clickable) {
        Material mat = seeds == 0 ? Material.GRAY_DYE : Material.COCOA_BEANS;
        ItemStack item = new ItemStack(mat, Math.max(1, Math.min(seeds, 64)));
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName((clickable ? "\u00A7a" : "\u00A7c") + seeds + " seeds"
                + (clickable ? " \u00A77(click to sow)" : ""));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack statusItem(OwareBoard board, boolean forPlayerA) {
        ItemStack item = new ItemStack(Material.GOLDEN_APPLE);
        ItemMeta meta = item.getItemMeta();
        int myScore = forPlayerA ? board.getScoreA() : board.getScoreB();
        int oppScore = forPlayerA ? board.getScoreB() : board.getScoreA();
        boolean myTurn = board.isPlayerATurn() == forPlayerA;
        meta.setDisplayName(myTurn ? "\u00A7aYour turn" : "\u00A77Opponent's turn");
        meta.setLore(List.of(
                "\u00A7fYour captures: \u00A7e" + myScore,
                "\u00A7fOpponent captures: \u00A7e" + oppScore,
                "\u00A78Wager: \u00A76\u20B5" + board.wager
        ));
        item.setItemMeta(meta);
        return item;
    }
}
