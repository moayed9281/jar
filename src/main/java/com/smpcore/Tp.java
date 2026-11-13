package com.smpcore;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public final class Tp {
    private final SMPCore plugin;
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();

    public Tp(SMPCore plugin) {
        this.plugin = plugin;
    }

    /** Teleports after the configured delay. dest is evaluated when the teleport happens. */
    public void go(Player p, Supplier<Location> dest, String targetName) {
        int delay = plugin.getConfig().getInt("teleport.delay", 3);
        if (delay <= 0 || p.hasPermission("smpcore.bypass.delay")) {
            finish(p, dest, targetName);
            return;
        }
        if (!active.add(p.getUniqueId())) {
            plugin.msg.send(p, "tp-already");
            return;
        }
        boolean cancelOnMove = plugin.getConfig().getBoolean("teleport.cancel-on-move", true);
        Location start = p.getLocation();
        plugin.msg.send(p, "tp-countdown", "seconds", String.valueOf(delay));
        new BukkitRunnable() {
            int ticks = 0;

            @Override
            public void run() {
                if (!p.isOnline()) {
                    active.remove(p.getUniqueId());
                    cancel();
                    return;
                }
                Location now = p.getLocation();
                if (cancelOnMove && (now.getWorld() != start.getWorld() || now.distanceSquared(start) > 0.25)) {
                    active.remove(p.getUniqueId());
                    plugin.msg.send(p, "tp-cancelled");
                    cancel();
                    return;
                }
                ticks += 5;
                if (ticks >= delay * 20) {
                    active.remove(p.getUniqueId());
                    cancel();
                    finish(p, dest, targetName);
                }
            }
        }.runTaskTimer(plugin, 5L, 5L);
    }

    private void finish(Player p, Supplier<Location> dest, String targetName) {
        Location l = dest.get();
        if (l == null) {
            plugin.msg.send(p, "tpa-offline");
            return;
        }
        p.teleportAsync(l);
        plugin.msg.send(p, "tp-done", "target", targetName);
    }
}
