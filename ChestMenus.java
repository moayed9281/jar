package com.smpcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fallback for versions without the Dialog API (older than 1.21.6). */
public final class ChestMenus implements Menus, Listener {
    private static final class Holder implements InventoryHolder {
        final Map<Integer, Runnable> actions = new HashMap<>();
        Inventory inv;
        java.util.UUID owner;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private final SMPCore plugin;

    public ChestMenus(SMPCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public void open(Player p, Component title, Component message, List<Entry> entries, Entry back) {
        if (entries.isEmpty() && back == null) {
            if (message != null) p.sendMessage(message);
            return;
        }
        int cap = back != null ? 53 : 54;
        List<Entry> list = new ArrayList<>(entries.size() > cap ? entries.subList(0, cap) : entries);
        int count = list.size() + (back != null ? 1 : 0);
        int rows = Math.max(1, Math.min(6, (count + 8) / 9));
        Holder h = new Holder();
        Inventory inv = Bukkit.createInventory(h, rows * 9, title);
        h.inv = inv;
        h.owner = p.getUniqueId();
        int i = 0;
        for (Entry e : list) {
            inv.setItem(i, item(e));
            h.actions.put(i, e.action());
            i++;
        }
        if (back != null) {
            int slot = rows * 9 - 1;
            inv.setItem(slot, item(back));
            h.actions.put(slot, back.action());
        }
        p.openInventory(inv);
    }

    private ItemStack item(Entry e) {
        org.bukkit.Material mat = e.icon() == null || e.icon().isAir() ? org.bukkit.Material.PAPER : e.icon();
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(e.label().decoration(TextDecoration.ITALIC, false));
        if (e.tooltip() != null) meta.lore(List.of(e.tooltip().decoration(TextDecoration.ITALIC, false)));
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getInventory().getHolder() instanceof Holder h) {
            e.setCancelled(true);
            Runnable r = h.actions.get(e.getRawSlot());
            if (r != null && e.getWhoClicked() instanceof Player pl && pl.getUniqueId().equals(h.owner)) {
                pl.closeInventory();
                Bukkit.getScheduler().runTask(plugin, r);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }
}
