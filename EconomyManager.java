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
        record("Economy", text, 0xFFD700);
    }

    /** One line in the console, economy-log.txt and the Discord economy channel. Safe from any thread. */
    public void record(String title, String text, int color) {
        String flat = text.replace("\n", " | ");
        plugin.getLogger().info("[ECO] " + title + ": " + flat);
        plugin.discord.logEconomy(title, text, color);
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " " + title + ": " + flat + System.lineSeparator();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Files.writeString(new File(plugin.getDataFolder(), "economy-log.txt").toPath(), line,
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) { }
        });
    }

    // ------------------------------------------------------------ transaction log
    private boolean logOn(String type) {
        var c = plugin.getConfig();
        return c.getBoolean("economy.log.enabled", true) && c.getBoolean("economy.log." + type, true);
    }

    /** Bounty placed / claimed (economy.log.bounty). */
    public void logBounty(String title, String text, int color) {
        if (logOn("bounty")) record(title, text, color);
    }

    /** /eco give | take | set. Always logged. */
    public void logAdmin(String staff, String sub, String target, double amt, double before, double after) {
        String title = switch (sub) {
            case "give" -> "Money given (admin)";
            case "take" -> "Money taken (admin)";
            default -> "Balance set (admin)";
        };
        int color = sub.equals("give") ? 0x55FF55 : sub.equals("take") ? 0xFF5555 : 0xFFAA00;
        record(title, staff + " /eco " + sub + " " + target + " " + format(amt) + "\nBalance: "
                + format(before) + " -> " + format(after), color);
    }

    /** A player-to-player payment. */
    public void logPay(String from, String to, double amt) {
        if (logOn("pay")) {
            record("Payment", from + " paid " + to + " " + format(amt), 0xFFD700);
        } else if (amt >= plugin.getConfig().getDouble("economy.log-pay-over", 100000)) {
            audit(from + " paid " + to + " " + format(amt));
        }
    }

    /** RTP prices, refunds and the Discord link reward. type = rtp | rewards. */
    public void logMisc(String type, String title, UUID id, double amt, boolean taken) {
        if (!logOn(type)) return;
        record(title, plugin.players.nameFor(id) + (taken ? " paid " : " received ") + format(amt), taken ? 0xFF5555 : 0x55FF55);
    }

    private static final class Agg {
        final String who;
        final boolean taken;
        final String src;
        double sum;
        int count;
        double balance;

        Agg(String who, boolean taken, String src) {
            this.who = who;
            this.taken = taken;
            this.src = src;
        }
    }

    private final Map<String, Agg> aggs = new HashMap<>();

    /**
     * Money moved through Vault by another plugin (EconomyShopGUI buy / sell, quests, ...).
     * taken = true: the player paid. Many moves of the same kind within 2 seconds (sell all) become one line.
     */
    public void logVault(UUID id, boolean taken, double amt, double balanceAfter) {
        if (!logOn("shop")) return;
        if (amt < plugin.getConfig().getDouble("economy.log.shop-min-amount", 0)) return;
        String src = callerPlugin();
        String who = plugin.players.nameFor(id);
        String key = id + "|" + taken + "|" + src;
        synchronized (aggs) {
            Agg a = aggs.get(key);
            if (a == null) {
                a = new Agg(who, taken, src);
                aggs.put(key, a);
                Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> flush(key), 40L);
            }
            a.sum += amt;
            a.count++;
            a.balance = balanceAfter;
        }
    }

    private void flush(String key) {
        Agg a;
        synchronized (aggs) {
            a = aggs.remove(key);
        }
        if (a == null) return;
        String title = a.taken ? "Money taken (shop / purchase)" : "Money given (shop / sale)";
        String text = a.who + (a.taken ? " paid " : " received ") + format(a.sum) + "\nBy: " + a.src
                + (a.count > 1 ? " (" + a.count + " transactions)" : "") + "\nBalance now: " + format(a.balance);
        record(title, text, a.taken ? 0xFF5555 : 0x55FF55);
    }

    /** Name of the plugin that called Vault (first plugin on the call stack that is not us or Vault). */
    private String callerPlugin() {
        try {
            return StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(s -> s
                    .map(StackWalker.StackFrame::getDeclaringClass)
                    .map(cl -> {
                        try {
                            return org.bukkit.plugin.java.JavaPlugin.getProvidingPlugin(cl);
                        } catch (Throwable t) {
                            return null;
                        }
                    })
                    .filter(pl -> pl != null && pl != plugin && !pl.getName().equalsIgnoreCase("Vault"))
                    .map(org.bukkit.plugin.Plugin::getName)
                    .findFirst().orElse("another plugin"));
        } catch (Throwable t) {
            return "another plugin";
        }
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
                try (PreparedStatement ps = c.prepareStatement(plugin.net.dialect.balanceSet())) {
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
            try (PreparedStatement ps = c.prepareStatement(plugin.net.dialect.balanceDelta())) {
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
            try (PreparedStatement ins = c.prepareStatement(plugin.net.dialect.balanceInsertIgnore())) {
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
        logPay(from.getName(), toName, amt);
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
            try (PreparedStatement ps = c.prepareStatement(plugin.net.dialect.balanceInsertIgnore())) {
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
                try (PreparedStatement ps = c.prepareStatement(plugin.net.dialect.balanceInsertIgnore())) {
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
