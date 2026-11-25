package com.smpcore;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bounties: players put money on someone's head, whoever kills that player (PvP) collects all of it.
 * Money comes from the placer's balance. Shared across servers in network mode.
 */
public final class BountyManager implements Listener {
    public static final class Bounty {
        public String name = "?";
        public double total;
        public final Map<UUID, Double> by = new LinkedHashMap<>();
    }

    private final SMPCore plugin;
    private final File file;
    private final Map<UUID, Bounty> map = new HashMap<>();

    public BountyManager(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bounties.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = yml.getConfigurationSection("bounties");
        if (s == null) return;
        for (String key : s.getKeys(false)) {
            try {
                map.put(UUID.fromString(key), fromJson(s.getString(key, "{}")));
            } catch (RuntimeException ignored) { }
        }
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("bounty.enabled", true) && plugin.economy.enabled();
    }

    // ------------------------------------------------------------ reading
    public synchronized double total(UUID target) {
        Bounty b = map.get(target);
        return b == null ? 0 : b.total;
    }

    /** Biggest bounties first. */
    public synchronized List<Map.Entry<UUID, Bounty>> top(int n) {
        List<Map.Entry<UUID, Bounty>> l = new ArrayList<>(map.entrySet());
        l.sort((a, b) -> Double.compare(b.getValue().total, a.getValue().total));
        return new ArrayList<>(l.subList(0, Math.min(n, l.size())));
    }

    // ------------------------------------------------------------ placing
    public void place(Player from, UUID targetId, String targetName, double amt) {
        Msg m = plugin.msg;
        EconomyManager e = plugin.economy;
        if (!enabled()) {
            m.send(from, e.enabled() ? "bounty-disabled" : "economy-disabled");
            return;
        }
        if (from.getUniqueId().equals(targetId)) {
            m.send(from, "bounty-self");
            return;
        }
        if (Double.isNaN(amt) || Double.isInfinite(amt) || amt <= 0) {
            m.send(from, "pay-invalid");
            return;
        }
        double min = plugin.getConfig().getDouble("bounty.min-amount", 1000);
        if (amt < min) {
            m.send(from, "bounty-min", "amount", e.format(min));
            return;
        }
        if (!e.withdraw(from.getUniqueId(), amt)) {
            m.send(from, "pay-no-money");
            return;
        }
        double total;
        synchronized (this) {
            Bounty b = map.computeIfAbsent(targetId, k -> new Bounty());
            b.name = targetName;
            b.total += amt;
            b.by.merge(from.getUniqueId(), amt, Double::sum);
            total = b.total;
            persist(targetId);
        }
        e.logBounty("Bounty placed", from.getName() + " put " + e.format(amt) + " on " + targetName
                + "\nTotal bounty: " + e.format(total), 0xFFAA00);
        m.send(from, "bounty-placed", "amount", e.format(amt), "player", targetName, "total", e.format(total));
        if (plugin.getConfig().getBoolean("bounty.announce", true)) {
            Bukkit.broadcast(m.prefixed("bounty-broadcast", "player", from.getName(), "target", targetName,
                    "amount", e.format(amt), "total", e.format(total)));
        } else {
            Player t = Bukkit.getPlayer(targetId);
            if (t != null) m.send(t, "bounty-on-you", "player", from.getName(), "amount", e.format(amt));
        }
    }

    // ------------------------------------------------------------ claiming
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent ev) {
        Player victim = ev.getPlayer();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId()) || !enabled()) return;
        Bounty b;
        synchronized (this) {
            b = map.remove(victim.getUniqueId());
            if (b == null) return;
            persistRemove(victim.getUniqueId());
        }
        EconomyManager e = plugin.economy;
        e.deposit(killer.getUniqueId(), b.total);
        e.logBounty("Bounty claimed", killer.getName() + " killed " + victim.getName() + " and collected "
                + e.format(b.total), 0x55FF55);
        plugin.msg.send(killer, "bounty-claimed", "amount", e.format(b.total), "player", victim.getName());
        if (plugin.getConfig().getBoolean("bounty.announce", true)) {
            Bukkit.broadcast(plugin.msg.prefixed("bounty-claimed-broadcast", "killer", killer.getName(),
                    "player", victim.getName(), "amount", e.format(b.total)));
        }
    }

    // ------------------------------------------------------------ saving / network
    private static String toJson(Bounty b) {
        JsonObject o = new JsonObject();
        o.addProperty("n", b.name);
        o.addProperty("t", b.total);
        JsonObject by = new JsonObject();
        for (Map.Entry<UUID, Double> e : b.by.entrySet()) by.addProperty(e.getKey().toString(), e.getValue());
        o.add("by", by);
        return o.toString();
    }

    private static Bounty fromJson(String s) {
        JsonObject o = new JsonParser().parse(s).getAsJsonObject();
        Bounty b = new Bounty();
        b.name = o.has("n") ? o.get("n").getAsString() : "?";
        b.total = o.has("t") ? o.get("t").getAsDouble() : 0;
        if (o.has("by")) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("by").entrySet()) {
                b.by.put(UUID.fromString(e.getKey()), e.getValue().getAsDouble());
            }
        }
        return b;
    }

    private void persist(UUID target) {
        if (plugin.net != null) plugin.net.put("bounty", target.toString(), toJson(map.get(target)));
        else saveLater();
    }

    private void persistRemove(UUID target) {
        if (plugin.net != null) plugin.net.remove("bounty", target.toString());
        else saveLater();
    }

    private void saveLater() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::save);
    }

    synchronized void save() {
        if (plugin.net != null) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Bounty> e : map.entrySet()) yml.set("bounties." + e.getKey(), toJson(e.getValue()));
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save bounties.yml: " + ex.getMessage());
        }
    }

    public synchronized void applyRemote(String key, String value, boolean deleted) {
        UUID target = UUID.fromString(key);
        if (deleted || value == null) map.remove(target);
        else map.put(target, fromJson(value));
    }

    public synchronized void exportLocal() {
        for (Map.Entry<UUID, Bounty> e : map.entrySet()) {
            plugin.net.putIfAbsent("bounty", e.getKey().toString(), toJson(e.getValue()));
        }
    }
}
