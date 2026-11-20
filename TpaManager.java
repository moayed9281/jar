package com.smpcore;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TpaManager {
    /** fromServer == null means the sender is on this server. */
    public record Req(UUID from, String fromName, String fromServer, UUID to, boolean here, long expires) {
        boolean remote() {
            return fromServer != null;
        }
    }

    private final SMPCore plugin;
    private final Map<UUID, LinkedHashMap<UUID, Req>> pending = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Map<UUID, Set<String>> outgoing = new HashMap<>();

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

    private static String key(UUID to, UUID from) {
        return to + "_" + from;
    }

    /** Shared checks for local and cross-server requests. Sends the error message itself. */
    private boolean check(Player from, UUID toId, boolean here) {
        Msg m = plugin.msg;
        var cfg = plugin.getConfig();
        if (from.getUniqueId().equals(toId)) {
            m.send(from, "tpa-self");
            return false;
        }
        if (!cfg.getBoolean(here ? "tpa.tpahere-enabled" : "tpa.tpa-enabled", true)) {
            m.send(from, "tpa-disabled");
            return false;
        }
        long need = cfg.getLong(here ? "tpa.tpahere-min-playtime-minutes" : "tpa.tpa-min-playtime-minutes", 0);
        if (need > 0 && !from.hasPermission("smpcore.bypass.playtime")) {
            long have = playMinutes(from);
            if (have < need) {
                m.send(from, "tpa-playtime", "minutes", String.valueOf(need - have), "required", String.valueOf(need));
                return false;
            }
        }
        if (!plugin.settings.acceptsTpa(toId) && !from.hasPermission("smpcore.bypass.tpablock")) {
            m.send(from, "tpa-blocked", "player", plugin.players.nameFor(toId));
            return false;
        }
        long now = System.currentTimeMillis();
        long cd = cfg.getLong("tpa.cooldown", 10) * 1000L;
        Long last = cooldown.get(from.getUniqueId());
        if (last != null && now - last < cd && !from.hasPermission("smpcore.bypass.cooldown")) {
            m.send(from, "tpa-cooldown", "seconds", String.valueOf((cd - (now - last)) / 1000 + 1));
            return false;
        }
        cooldown.put(from.getUniqueId(), now);
        return true;
    }

    private long expiry() {
        return System.currentTimeMillis() + plugin.getConfig().getLong("tpa.expire", 60) * 1000L;
    }

    // ------------------------------------------------------------ sending
    public void request(Player from, Player to, boolean here) {
        if (!check(from, to.getUniqueId(), here)) return;
        Msg m = plugin.msg;
        pending.computeIfAbsent(to.getUniqueId(), k -> new LinkedHashMap<>())
                .put(from.getUniqueId(), new Req(from.getUniqueId(), from.getName(), null, to.getUniqueId(), here, expiry()));
        m.send(from, "tpa-sent", "player", to.getName());
        m.send(to, here ? "tpahere-received" : "tpa-received", "player", from.getName());
        if (plugin.getConfig().getBoolean("tpa.incoming-menu", true)) {
            plugin.gui.openIncoming(to, from.getName(), here);
        }
    }

    /** Request to a player that is on another server of the network. */
    public void requestRemote(Player from, Net.OnlineInfo to, boolean here) {
        if (plugin.net == null || !check(from, to.id(), here)) return;
        JsonObject o = new JsonObject();
        o.addProperty("fn", from.getName());
        o.addProperty("fs", plugin.serverName());
        o.addProperty("h", here);
        o.addProperty("x", expiry());
        String k = key(to.id(), from.getUniqueId());
        plugin.net.put("tpa", k, o.toString());
        outgoing.computeIfAbsent(from.getUniqueId(), x -> new HashSet<>()).add(k);
        plugin.msg.send(from, "tpa-sent", "player", to.name());
    }

    /** A request that another server stored for one of our players. */
    public void applyRemote(String key, String value, boolean deleted) {
        String[] parts = key.split("_");
        if (parts.length != 2) return;
        UUID to;
        UUID from;
        try {
            to = UUID.fromString(parts[0]);
            from = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            return;
        }
        if (deleted || value == null) {
            LinkedHashMap<UUID, Req> m = pending.get(to);
            if (m != null) {
                Req r = m.get(from);
                if (r != null && r.remote()) m.remove(from);
            }
            return;
        }
        JsonObject o = new JsonParser().parse(value).getAsJsonObject();
        long exp = o.get("x").getAsLong();
        if (exp < System.currentTimeMillis()) return;
        Player target = Bukkit.getPlayer(to);
        if (target == null) return;
        String fs = o.get("fs").getAsString();
        if (fs.equalsIgnoreCase(plugin.serverName())) return;
        LinkedHashMap<UUID, Req> map = pending.computeIfAbsent(to, k -> new LinkedHashMap<>());
        Req ex = map.get(from);
        if (ex != null && ex.expires() == exp) return;
        boolean here = o.get("h").getAsBoolean();
        String fn = o.get("fn").getAsString();
        map.put(from, new Req(from, fn, fs, to, here, exp));
        plugin.msg.send(target, here ? "tpahere-received" : "tpa-received", "player", fn);
        if (plugin.getConfig().getBoolean("tpa.incoming-menu", true)) plugin.gui.openIncoming(target, fn, here);
    }

    // ------------------------------------------------------------ answering
    private Req find(Player target, String fromName) {
        LinkedHashMap<UUID, Req> map = pending.get(target.getUniqueId());
        if (map == null) return null;
        long now = System.currentTimeMillis();
        for (Iterator<Req> it = map.values().iterator(); it.hasNext(); ) {
            if (it.next().expires() < now) it.remove();
        }
        Req found = null;
        for (Req r : map.values()) {
            if (fromName == null || r.fromName().equalsIgnoreCase(fromName)) found = r;
        }
        return found;
    }

    private void drop(Req r) {
        LinkedHashMap<UUID, Req> map = pending.get(r.to());
        if (map != null) map.remove(r.from());
    }

    public void accept(Player target, String fromName) {
        Msg m = plugin.msg;
        Req r = find(target, fromName);
        if (r == null) {
            m.send(target, "tpa-none");
            return;
        }
        drop(r);
        if (!r.remote()) {
            Player from = Bukkit.getPlayer(r.from());
            if (from == null) {
                m.send(target, "tpa-offline");
                return;
            }
            m.send(target, "tpa-accepted");
            m.send(from, "tpa-accepted-sender", "player", target.getName());
            Player mover = r.here() ? target : from;
            Player dest = r.here() ? from : target;
            plugin.tp.go(mover, () -> dest.isOnline() ? dest.getLocation() : null, dest.getName());
            return;
        }
        Net net = plugin.net;
        if (net == null) {
            m.send(target, "tpa-offline");
            return;
        }
        net.remove("tpa", key(target.getUniqueId(), r.from()));
        m.send(target, "tpa-accepted");
        net.notifyPlayer(r.from(), "tpa-accepted-sender", "player", target.getName());
        String thisServer = plugin.serverName();
        if (!r.here()) {
            // the sender travels here, then is teleported to the target
            net.movePlayerToPlayer(r.from(), thisServer, target.getUniqueId(),
                    () -> net.connectOther(target, r.fromName(), thisServer));
        } else {
            // the target travels to the sender's server
            plugin.tp.countdown(target, () -> net.movePlayerToPlayer(target.getUniqueId(), r.fromServer(), r.from(),
                    () -> net.connect(target, r.fromServer())));
        }
    }

    public void deny(Player target, String fromName) {
        Msg m = plugin.msg;
        Req r = find(target, fromName);
        if (r == null) {
            m.send(target, "tpa-none");
            return;
        }
        drop(r);
        m.send(target, "tpa-denied");
        if (!r.remote()) {
            Player from = Bukkit.getPlayer(r.from());
            if (from != null) m.send(from, "tpa-denied-sender", "player", target.getName());
        } else if (plugin.net != null) {
            plugin.net.remove("tpa", key(target.getUniqueId(), r.from()));
            plugin.net.notifyPlayer(r.from(), "tpa-denied-sender", "player", target.getName());
        }
    }

    public void cancel(Player from) {
        for (LinkedHashMap<UUID, Req> map : pending.values()) map.remove(from.getUniqueId());
        Set<String> keys = outgoing.remove(from.getUniqueId());
        if (keys != null && plugin.net != null) {
            for (String k : keys) plugin.net.remove("tpa", k);
        }
        plugin.msg.send(from, "tpa-cancelled");
    }
}
