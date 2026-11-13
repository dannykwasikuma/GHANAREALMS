package com.ghanarealms.shops;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.HashMap;
import java.util.Map;

/** Marks an inventory as a server-shop menu and remembers what each slot means. */
public class ShopMenuHolder implements InventoryHolder {

    public final String categoryId;
    public final int page;
    public final Map<Integer, ServerShops.Entry> entrySlots = new HashMap<>();
    public final Map<Integer, String> categorySlots = new HashMap<>(); // hub only
    public int prevSlot = -1;
    public int nextSlot = -1;
    public int backSlot = -1;
    public int balanceSlot = -1;
    private Inventory inventory;

    public ShopMenuHolder(String categoryId, int page) {
        this.categoryId = categoryId;
        this.page = page;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
