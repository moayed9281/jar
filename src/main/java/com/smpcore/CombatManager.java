package com.smpcore;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Combat timer: after a fight, DiamondCraft commands, menus and teleports are blocked for combat.seconds. */
public final class CombatManager implements Listener {
    private final SMPCore plugin;
    private final Map<UUID, Long> until = new ConcurrentHashMap<>();

    public CombatManager(SMPCore plugin) {
        this.plugin = plugin;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("combat.enabled", true);
    }

    private int seconds() {
        return Math.max(1, plugin.getConfig().getInt("combat.seconds", 20));
    }

    /** Seconds of combat left (0 = not in combat). */
    public int remaining(Player p) {
        if (!enabled()) return 0;
        Long t = until.get(p.getUniqueId());
        if (t == null) return 0;
        long left = t - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) Math.ceil(left / 1000.0);
    }

    public boolean inCombat(Player p) {
        return remaining(p) > 0;
    }

    /** In combat and not allowed to ignore it. */
    public boolean restricted(Player p) {
        return inCombat(p) && !p.hasPermission("smpcore.combat.bypass");
    }

    /** True when this DiamondCraft command must not run right now. */
    public boolean blocksCommand(Player p, String commandName) {
        if (!restricted(p)) return false;
        String n = commandName.toLowerCase(Locale.ROOT);
        for (String x : plugin.getConfig().getStringList("combat.allowed-commands")) {
            if (x.equalsIgnoreCase(n)) return false;
        }
        return true;
    }

    /** Wraps a menu action: while in combat it only shows the "in combat" message. */
    public Runnable guard(Player p, Runnable action) {
        return () -> {
            if (restricted(p)) {
                p.sendMessage(plugin.msg.prefixed("combat-blocked", "seconds", String.valueOf(remaining(p))));
                return;
            }
            action.run();
        };
    }

    private void tag(Player p) {
        if (p.hasPermission("smpcore.combat.bypass")) return;
        boolean was = inCombat(p);
        until.put(p.getUniqueId(), System.currentTimeMillis() + seconds() * 1000L);
        if (!was) plugin.msg.send(p, "combat-tagged", "seconds", String.valueOf(seconds()));
    }

    private void tick() {
        boolean bar = plugin.getConfig().getBoolean("combat.actionbar", true);
        for (Iterator<Map.Entry<UUID, Long>> it = until.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Long> e = it.next();
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) {
                it.remove();
            } else if (e.getValue() <= System.currentTimeMillis()) {
                it.remove();
                plugin.msg.send(p, "combat-ended");
            } else if (bar && enabled()) {
                p.sendActionBar(plugin.msg.get("combat-actionbar", "seconds", String.valueOf(remaining(p))));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!enabled() || !(e.getEntity() instanceof Player victim)) return;
        Player attacker = null;
        Entity d = e.getDamager();
        if (d instanceof Player pl) attacker = pl;
        else if (d instanceof Projectile pr && pr.getShooter() instanceof Player pl) attacker = pl;
        if (attacker != null) {
            if (attacker.getUniqueId().equals(victim.getUniqueId())) return;
            tag(victim);
            tag(attacker);
        } else if (!plugin.getConfig().getBoolean("combat.pvp-only", true)) {
            tag(victim);
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        until.remove(e.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        until.remove(e.getPlayer().getUniqueId());
    }

    /** Optional: commands of OTHER plugins (combat.extra-blocked-commands), e.g. shop, spawn. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onOtherCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!restricted(p)) return;
        String name = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(colon + 1);
        for (String x : plugin.getConfig().getStringList("combat.extra-blocked-commands")) {
            if (x.equalsIgnoreCase(name)) {
                e.setCancelled(true);
                p.sendMessage(plugin.msg.prefixed("combat-blocked", "seconds", String.valueOf(remaining(p))));
                return;
            }
        }
    }
}
