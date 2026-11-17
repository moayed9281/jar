package com.smpcore;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Built-in economy. Turn it off with economy.enabled: false. Shared across servers in network mode. */
public final class EconomyManager {
    private final SMPCore plugin;
    private final File file;
    private final Map<UUID, Double> bal = new ConcurrentHashMap<>();
    private final Map<UUID, Long> touched = new ConcurrentHashMap<>();
    private volatile boolean dirty;
    private volatile List<Map.Entry<UUID, Double>> topCache = List.of();
    private volatile long topAt;

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
            if (dirty && plugin.net == null) save();
        }, 6000L, 6000L);
    }

    private boolean net() {
        return plugin.net != null;
    }

    public boolean enabled() {
        return plugin.getConfig().getBoolean("economy.enabled", true);
    }

    private double start() {
        return plugin.getConfig().getDouble("economy.starting-balance", 100);
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private double maxBalance() {
        return Math.max(1, plugin.getConfig().getDouble("economy.max-balance", 1_000_000_000_000.0));
    }

    private static boolean bad(double v) {
        return Double.isNaN(v) || Double.isInfinite(v);
    }

    /** Admin/large-payment audit trail (plugins/DiamondCraft/economy-log.txt). */
    public void audit(String text) {
        plugin.getLogger().info("[ECO] " + text);
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " " + text + System.lineSeparator();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Files.writeString(new File(plugin.getDataFolder(), "economy-log.txt").toPath(), line,
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) { }
        });
    }

    // ------------------------------------------------------------ reading
    public void ensure(UUID id) {
        if (net()) return;
        if (bal.putIfAbsent(id, start()) == null) dirty = true;
    }

    public double get(UUID id) {
        Double v = bal.get(id);
        if (v != null) return v;
        if (net()) {
            Double d = readDb(id);
            if (d == null) return start();
            bal.put(id, d);
            return d;
        }
        return start();
    }

    public boolean has(UUID id, double amt) {
        return get(id) + 0.0001 >= amt;
    }

    private Double readDb(UUID id) {
        try (Connection c = plugin.net.conn();
             PreparedStatement ps = c.prepareStatement("SELECT balance FROM dc_balance WHERE uuid=?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : start();
            }
        } catch (SQLException e) {
            return null;
        }
    }

    // ------------------------------------------------------------ writing
    private void setCache(UUID id, double v) {
        bal.put(id, round(Math.min(maxBalance(), Math.max(0, v))));
        touched.put(id, System.currentTimeMillis());
        dirty = true;
    }

    public synchronized void set(UUID id, double v) {
        if (bad(v)) return;
        setCache(id, v);
        if (net()) {
            double nv = bal.get(id);
            plugin.net.async(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO dc_balance (uuid,balance,updated) VALUES (?,?,"
                        + Net.NOW + ") ON DUPLICATE KEY UPDATE balance=VALUES(balance), updated=" + Net.NOW)) {
                    ps.setString(1, id.toString());
                    ps.setDouble(2, nv);
                    ps.executeUpdate();
                }
            });
        }
    }

    private void delta(UUID id, double d) {
        if (!net()) return;
        double base = Math.max(0, start() + d);
        plugin.net.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO dc_balance (uuid,balance,updated) VALUES (?,?,"
                    + Net.NOW + ") ON DUPLICATE KEY UPDATE balance = GREATEST(0, balance + ?), updated=" + Net.NOW)) {
                ps.setString(1, id.toString());
                ps.setDouble(2, base);
                ps.setDouble(3, d);
                ps.executeUpdate();
            }
        });
    }

    /** Takes money. Fails for zero, negative, NaN or infinite amounts. In network mode this is one atomic database step. */
    public synchronized boolean withdraw(UUID id, double amt) {
        if (bad(amt) || amt <= 0) return false;
        if (net()) return withdrawNet(id, amt);
        if (!has(id, amt)) return false;
        setCache(id, get(id) - amt);
        return true;
    }

    private boolean withdrawNet(UUID id, double amt) {
        try (Connection c = plugin.net.conn()) {
            try (PreparedStatement ins = c.prepareStatement("INSERT IGNORE INTO dc_balance (uuid,balance,updated) VALUES (?,?,"
                    + Net.NOW + ")")) {
                ins.setString(1, id.toString());
                ins.setDouble(2, start());
                ins.executeUpdate();
            }
            boolean ok;
            try (PreparedStatement ps = c.prepareStatement("UPDATE dc_balance SET balance = balance - ?, updated=" + Net.NOW
                    + " WHERE uuid=? AND balance >= ?")) {
                ps.setDouble(1, amt);
                ps.setString(2, id.toString());
                ps.setDouble(3, amt);
                ok = ps.executeUpdate() > 0;
            }
            try (PreparedStatement q = c.prepareStatement("SELECT balance FROM dc_balance WHERE uuid=?")) {
                q.setString(1, id.toString());
                try (ResultSet rs = q.executeQuery()) {
                    if (rs.next()) {
                        bal.put(id, rs.getDouble(1));
                        touched.put(id, System.currentTimeMillis());
                    }
                }
            }
            return ok;
        } catch (SQLException e) {
            plugin.getLogger().warning("Withdraw refused (database error): " + e.getMessage());
            return false;
        }
    }

    /** Adds money. Ignores zero, negative, NaN or infinite amounts. */
    public synchronized void deposit(UUID id, double amt) {
        if (bad(amt) || amt <= 0) return;
        double before = get(id);
        double after = Math.min(maxBalance(), before + amt);
        setCache(id, after);
        delta(id, after - before);
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
        if (bad(amt) || amt <= 0) {
            m.send(from, "pay-invalid");
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
        if (amt >= plugin.getConfig().getDouble("economy.log-pay-over", 100000)) {
            audit(from.getName() + " paid " + toName + " " + format(amt));
        }
        m.send(from, "pay-sent", "amount", format(amt), "player", toName);
        Player to = Bukkit.getPlayer(toId);
        if (to != null) {
            m.send(to, "pay-received", "amount", format(amt), "player", from.getName());
        } else if (net()) {
            plugin.net.notifyPlayer(toId, "pay-received", "amount", format(amt), "player", from.getName());
        }
    }

    // ------------------------------------------------------------ top list
    public List<Map.Entry<UUID, Double>> top(int n) {
        if (net()) {
            long now = System.currentTimeMillis();
            if (now - topAt > 30000) {
                topAt = now;
                plugin.net.async(c -> {
                    try (PreparedStatement ps = c.prepareStatement("SELECT uuid,balance FROM dc_balance ORDER BY balance DESC LIMIT 50");
                         ResultSet rs = ps.executeQuery()) {
                        List<Map.Entry<UUID, Double>> l = new ArrayList<>();
                        while (rs.next()) l.add(Map.entry(UUID.fromString(rs.getString(1)), rs.getDouble(2)));
                        topCache = l;
                    }
                });
            }
            List<Map.Entry<UUID, Double>> t = topCache;
            return t.subList(0, Math.min(n, t.size()));
        }
        List<Map.Entry<UUID, Double>> list = new ArrayList<>(bal.entrySet());
        list.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return list.subList(0, Math.min(n, list.size()));
    }

    // ------------------------------------------------------------ network
    /** Login (async thread): make sure the player has an account and load the newest balance. */
    public void loadBlocking(UUID id) {
        try (Connection c = plugin.net.conn()) {
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO dc_balance (uuid,balance,updated) VALUES (?,?,"
                    + Net.NOW + ")")) {
                ps.setString(1, id.toString());
                ps.setDouble(2, start());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM dc_balance WHERE uuid=?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) bal.put(id, rs.getDouble(1));
                }
            }
        } catch (SQLException ignored) { }
    }

    /** Newest balances of the given (online) players. */
    public Map<UUID, Double> fetch(Collection<UUID> ids) {
        Map<UUID, Double> out = new HashMap<>();
        if (!net() || ids.isEmpty()) return out;
        StringBuilder sb = new StringBuilder("SELECT uuid,balance FROM dc_balance WHERE uuid IN (");
        for (int i = 0; i < ids.size(); i++) sb.append(i == 0 ? "?" : ",?");
        sb.append(")");
        try (Connection c = plugin.net.conn(); PreparedStatement ps = c.prepareStatement(sb.toString())) {
            int i = 1;
            for (UUID id : ids) ps.setString(i++, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(UUID.fromString(rs.getString(1)), rs.getDouble(2));
            }
        } catch (SQLException ignored) { }
        return out;
    }

    public void applyFetched(Map<UUID, Double> m) {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Double> e : m.entrySet()) {
            Long t = touched.get(e.getKey());
            if (t == null || now - t > 5000) bal.put(e.getKey(), e.getValue());
        }
    }

    public void exportLocal() {
        for (Map.Entry<UUID, Double> e : bal.entrySet()) {
            UUID id = e.getKey();
            double v = e.getValue();
            plugin.net.async(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO dc_balance (uuid,balance,updated) VALUES (?,?,"
                        + Net.NOW + ")")) {
                    ps.setString(1, id.toString());
                    ps.setDouble(2, v);
                    ps.executeUpdate();
                }
            });
        }
    }

    // ------------------------------------------------------------ local file (network mode off)
    public synchronized void save() {
        if (net()) return;
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
