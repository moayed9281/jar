package com.smpcore;

import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class TpaManager {
    public record Req(UUID from, UUID to, boolean here, long expires) { }

    private final SMPCore plugin;
    private final Map<UUID, LinkedHashMap<UUID, Req>> pending = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();

    private static final Statistic PLAYTIME = findPlaytime();

    private static Statistic findPlaytime() {
        try {
            return Statistic.valueOf("PLAY_TIME");
        } catch (IllegalArgumentException e) {
            return Statistic.valueOf("PLAY_ONE_MINUTE");
        }
    }

    public TpaManager(SMPCore plugin) {
        this.plugin = plugin;
    }

    public static long playMinutes(Player p) {
        return p.getStatistic(PLAYTIME) / 20L / 60L;
    }

    public void request(Player from, Player to, boolean here) {
        Msg m = plugin.msg;
        if (from.getUniqueId().equals(to.getUniqueId())) {
            m.send(from, "tpa-self");
            return;
        }
        var cfg = plugin.getConfig();
        if (!cfg.getBoolean(here ? "tpa.tpahere-enabled" : "tpa.tpa-enabled", true)) {
            m.send(from, "tpa-disabled");
            return;
        }
        long need = cfg.getLong(here ? "tpa.tpahere-min-playtime-minutes" : "tpa.tpa-min-playtime-minutes", 0);
        if (need > 0 && !from.hasPermission("smpcore.bypass.playtime")) {
            long have = playMinutes(from);
            if (have < need) {
                m.send(from, "tpa-playtime", "minutes", String.valueOf(need - have), "required", String.valueOf(need));
                return;
            }
        }
        if (!plugin.settings.acceptsTpa(to.getUniqueId()) && !from.hasPermission("smpcore.bypass.tpablock")) {
            m.send(from, "tpa-blocked", "player", to.getName());
            return;
        }
        long now = System.currentTimeMillis();
        long cd = plugin.getConfig().getLong("tpa.cooldown", 10) * 1000L;
        Long last = cooldown.get(from.getUniqueId());
        if (last != null && now - last < cd && !from.hasPermission("smpcore.bypass.cooldown")) {
            m.send(from, "tpa-cooldown", "seconds", String.valueOf((cd - (now - last)) / 1000 + 1));
            return;
        }
        cooldown.put(from.getUniqueId(), now);
        long exp = now + plugin.getConfig().getLong("tpa.expire", 60) * 1000L;
        pending.computeIfAbsent(to.getUniqueId(), k -> new LinkedHashMap<>())
                .put(from.getUniqueId(), new Req(from.getUniqueId(), to.getUniqueId(), here, exp));
        m.send(from, "tpa-sent", "player", to.getName());
        m.send(to, here ? "tpahere-received" : "tpa-received", "player", from.getName());
        if (plugin.getConfig().getBoolean("tpa.incoming-menu", true)) {
            plugin.gui.openIncoming(to, from, here);
        }
    }

    private Req find(Player target, String fromName) {
        LinkedHashMap<UUID, Req> map = pending.get(target.getUniqueId());
        if (map == null) return null;
        long now = System.currentTimeMillis();
        for (Iterator<Req> it = map.values().iterator(); it.hasNext(); ) {
            if (it.next().expires() < now) it.remove();
        }
        Req found = null;
        for (Req r : map.values()) {
            if (fromName == null) {
                found = r; // last one wins
            } else {
                Player p = Bukkit.getPlayer(r.from());
                if (p != null && p.getName().equalsIgnoreCase(fromName)) found = r;
            }
        }
        return found;
    }

    private void drop(Req r) {
        LinkedHashMap<UUID, Req> map = pending.get(r.to());
        if (map != null) map.remove(r.from());
    }

    public void accept(Player target, String fromName) {
        Req r = find(target, fromName);
        if (r == null) {
            plugin.msg.send(target, "tpa-none");
            return;
        }
        drop(r);
        Player from = Bukkit.getPlayer(r.from());
        if (from == null) {
            plugin.msg.send(target, "tpa-offline");
            return;
        }
        plugin.msg.send(target, "tpa-accepted");
        plugin.msg.send(from, "tpa-accepted-sender", "player", target.getName());
        Player mover = r.here() ? target : from;
        Player dest = r.here() ? from : target;
        plugin.tp.go(mover, () -> dest.isOnline() ? dest.getLocation() : null, dest.getName());
    }

    public void deny(Player target, String fromName) {
        Req r = find(target, fromName);
        if (r == null) {
            plugin.msg.send(target, "tpa-none");
            return;
        }
        drop(r);
        plugin.msg.send(target, "tpa-denied");
        Player from = Bukkit.getPlayer(r.from());
        if (from != null) plugin.msg.send(from, "tpa-denied-sender", "player", target.getName());
    }

    public void cancel(Player from) {
        for (LinkedHashMap<UUID, Req> map : pending.values()) map.remove(from.getUniqueId());
        plugin.msg.send(from, "tpa-cancelled");
    }
}
