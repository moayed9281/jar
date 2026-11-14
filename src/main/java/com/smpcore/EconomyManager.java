package com.smpcore;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Built-in economy. Turn it off with economy.enabled: false. */
public final class EconomyManager {
    private final SMPCore plugin;
    private final File file;
    private final Map<UUID, Double> bal = new ConcurrentHashMap<>();
    private volatile boolean dirty;

    public EconomyManager(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "balances.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = yml.getConfigurationSection("balances");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                try {
                    bal.put(UUID.fromString(k), s.getDouble(k));
                } catch (IllegalArgumentException ignored) { }
            }
        }
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (dirty) save();
        }, 6000L, 6000L);
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("economy.enabled", true);
    }

    private double start() {
        return plugin.getConfig().getDouble("economy.starting-balance", 100);
    }

    public void ensure(UUID id) {
        if (bal.putIfAbsent(id, start()) == null) dirty = true;
    }

    public double get(UUID id) {
        return bal.getOrDefault(id, start());
    }

    public boolean has(UUID id, double amt) {
        return get(id) + 0.0001 >= amt;
    }

    public synchronized void set(UUID id, double v) {
        bal.put(id, Math.round(Math.max(0, v) * 100.0) / 100.0);
        dirty = true;
    }

    public synchronized boolean withdraw(UUID id, double amt) {
        if (!has(id, amt)) return false;
        set(id, get(id) - amt);
        return true;
    }

    public synchronized void deposit(UUID id, double amt) {
        set(id, get(id) + amt);
    }

    public String format(double v) {
        String sym = plugin.getConfig().getString("economy.symbol", "$");
        String num = v == Math.floor(v) ? String.format(Locale.US, "%,.0f", v) : String.format(Locale.US, "%,.2f", v);
        return sym + num;
    }

    public void pay(Player from, UUID toId, String toName, double amt) {
        Msg m = plugin.msg;
        if (!enabled()) {
            m.send(from, "economy-disabled");
            return;
        }
        if (from.getUniqueId().equals(toId)) {
            m.send(from, "pay-self");
            return;
        }
        double min = plugin.getConfig().getDouble("economy.min-pay", 1);
        if (amt < min) {
            m.send(from, "pay-min", "amount", format(min));
            return;
        }
        if (!withdraw(from.getUniqueId(), amt)) {
            m.send(from, "pay-no-money");
            return;
        }
        deposit(toId, amt);
        m.send(from, "pay-sent", "amount", format(amt), "player", toName);
        Player to = Bukkit.getPlayer(toId);
        if (to != null) m.send(to, "pay-received", "amount", format(amt), "player", from.getName());
    }

    public List<Map.Entry<UUID, Double>> top(int n) {
        List<Map.Entry<UUID, Double>> list = new ArrayList<>(bal.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return list.subList(0, Math.min(n, list.size()));
    }

    public synchronized void save() {
        dirty = false;
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Double> e : bal.entrySet()) yml.set("balances." + e.getKey(), e.getValue());
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save balances.yml: " + ex.getMessage());
        }
    }
}
