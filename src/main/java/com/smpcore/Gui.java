package com.smpcore;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Gui {
    private final SMPCore plugin;

    public Gui(SMPCore plugin) {
        this.plugin = plugin;
    }

    private Menus.Entry back(Runnable action) {
        return new Menus.Entry(plugin.msg.text("menus.back-button"), null, Material.ARROW, action);
    }

    public void openHomes(Player p) {
        Msg m = plugin.msg;
        UUID id = p.getUniqueId();
        Map<String, Location> homes = plugin.homes.all(id);
        int limit = plugin.homes.limit(p);
        List<Menus.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, Location> en : homes.entrySet()) {
            String name = en.getKey();
            Location l = en.getValue();
            Component tip = m.mm("<gray>" + l.getWorld().getName() + " " + l.getBlockX() + ", "
                    + l.getBlockY() + ", " + l.getBlockZ());
            entries.add(new Menus.Entry(m.text("menus.home-button", "home", name), tip, Material.RED_BED,
                    () -> openHome(p, name)));
        }
        if (homes.size() < limit) {
            entries.add(new Menus.Entry(m.text("menus.set-home-button"), null, Material.LIME_DYE, () -> {
                String n = "home";
                int i = 2;
                while (homes.containsKey(n)) n = "home" + i++;
                plugin.homes.set(id, n, p.getLocation());
                m.send(p, "home-set", "home", n);
                openHomes(p);
            }));
        }
        Component title = m.text("menus.home-title", "used", String.valueOf(homes.size()),
                "limit", plugin.homes.limitText(p));
        plugin.menus.open(p, title, m.text("menus.home-info"), entries, null);
    }

    public void openHome(Player p, String name) {
        Msg m = plugin.msg;
        UUID id = p.getUniqueId();
        List<Menus.Entry> entries = new ArrayList<>();
        entries.add(new Menus.Entry(m.text("menus.teleport-button"), null, Material.ENDER_PEARL, () -> {
            plugin.tp.go(p, () -> plugin.homes.get(id, name), name);
        }));
        entries.add(new Menus.Entry(m.text("menus.delete-button"), null, Material.BARRIER, () -> {
            plugin.homes.delete(id, name);
            m.send(p, "home-deleted", "home", name);
            openHomes(p);
        }));
        plugin.menus.open(p, m.text("menus.home-actions-title", "home", name), null, entries,
                back(() -> openHomes(p)));
    }

    public void openTpa(Player p, boolean here) {
        Msg m = plugin.msg;
        List<Menus.Entry> entries = new ArrayList<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (other.getUniqueId().equals(p.getUniqueId())) continue;
            entries.add(new Menus.Entry(m.mm("<yellow>" + other.getName()), null, Material.PLAYER_HEAD,
                    () -> plugin.tpa.request(p, other, here)));
        }
        Component msg = entries.isEmpty() ? m.text("menus.tpa-no-players") : m.text("menus.tpa-info");
        plugin.menus.open(p, m.text(here ? "menus.tpahere-title" : "menus.tpa-title"), msg, entries, null);
    }

    public void openIncoming(Player target, Player from, boolean here) {
        Msg m = plugin.msg;
        List<Menus.Entry> entries = new ArrayList<>();
        entries.add(new Menus.Entry(m.text("menus.accept-button"), null, Material.LIME_CONCRETE,
                () -> plugin.tpa.accept(target, from.getName())));
        entries.add(new Menus.Entry(m.text("menus.deny-button"), null, Material.RED_CONCRETE,
                () -> plugin.tpa.deny(target, from.getName())));
        plugin.menus.open(target, m.text("menus.incoming-title"),
                m.text(here ? "menus.incoming-here" : "menus.incoming-to-you", "player", from.getName()),
                entries, null);
    }
}
