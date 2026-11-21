package com.smpcore;

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
        go(p, dest, targetName, null, null);
    }

    public void go(Player p, Supplier<Location> dest, String targetName, Runnable onDone) {
        go(p, dest, targetName, onDone, null);
    }

    /** onCancel runs if the teleport does NOT happen (moved, logged out, already teleporting). */
    public void go(Player p, Supplier<Location> dest, String targetName, Runnable onDone, Runnable onCancel) {
        countdown(p, () -> finish(p, dest, targetName, onDone, onCancel), onCancel);
    }

    public void countdown(Player p, Runnable after) {
        countdown(p, after, null);
    }

    /** Standing-still countdown (teleport.delay), then runs "after". */
    public void countdown(Player p, Runnable after, Runnable onCancel) {
        if (plugin.combat.restricted(p)) {
            p.sendMessage(plugin.msg.prefixed("combat-blocked", "seconds", String.valueOf(plugin.combat.remaining(p))));
            if (onCancel != null) onCancel.run();
            return;
        }
        int delay = plugin.getConfig().getInt("teleport.delay", 3);
        if (delay <= 0 || p.hasPermission("smpcore.bypass.delay")) {
            after.run();
            return;
        }
        if (!active.add(p.getUniqueId())) {
            plugin.msg.send(p, "tp-already");
            if (onCancel != null) onCancel.run();
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
                    if (onCancel != null) onCancel.run();
                    return;
                }
                if (plugin.combat.restricted(p)) {
                    active.remove(p.getUniqueId());
                    plugin.msg.send(p, "combat-tp-cancelled");
                    cancel();
                    if (onCancel != null) onCancel.run();
                    return;
                }
                Location now = p.getLocation();
                if (cancelOnMove && (now.getWorld() != start.getWorld() || now.distanceSquared(start) > 0.25)) {
                    active.remove(p.getUniqueId());
                    plugin.msg.send(p, "tp-cancelled");
                    cancel();
                    if (onCancel != null) onCancel.run();
                    return;
                }
                ticks += 5;
                if (ticks >= delay * 20) {
                    active.remove(p.getUniqueId());
                    cancel();
                    after.run();
                }
            }
        }.runTaskTimer(plugin, 5L, 5L);
    }

    private void finish(Player p, Supplier<Location> dest, String targetName, Runnable onDone, Runnable onCancel) {
        Location l = dest.get();
        if (l == null) {
            plugin.msg.send(p, "tpa-offline");
            if (onCancel != null) onCancel.run();
            return;
        }
        p.teleportAsync(l);
        plugin.msg.send(p, "tp-done", "target", targetName);
        if (onDone != null) onDone.run();
    }
}
