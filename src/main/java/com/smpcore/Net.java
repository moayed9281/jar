package com.smpcore;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Network mode: all servers share one MySQL/MariaDB database.
 * Data is kept in memory on every server and synced every few seconds.
 */
public final class Net {
    public record OnlineInfo(UUID id, String name, String server, long seen) { }

    public interface Sql {
        void run(Connection c) throws SQLException;
    }

    private record Row(String ns, String key, String value, boolean deleted) { }

    public static final String NOW = "CAST(UNIX_TIMESTAMP(NOW(3)) * 1000 AS UNSIGNED)";

    private final SMPCore plugin;
    private final HikariDataSource ds;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DiamondCraft-DB");
        t.setDaemon(true);
        return t;
    });
    private final Map<UUID, OnlineInfo> online = new ConcurrentHashMap<>();
    private final Map<String, Long> recent = new ConcurrentHashMap<>();
    private volatile List<UUID> onlineSnapshot = List.of();
    private volatile boolean polling;
    private volatile long since;

    public Net(SMPCore plugin) {
        this.plugin = plugin;
        var c = plugin.getConfig();
        HikariConfig hc = new HikariConfig();
        hc.setPoolName("DiamondCraft");
        hc.setDriverClassName("org.mariadb.jdbc.Driver");
        hc.setJdbcUrl("jdbc:mariadb://" + c.getString("network.database.host", "127.0.0.1") + ":"
                + c.getInt("network.database.port", 3306) + "/" + c.getString("network.database.name", "diamondcraft"));
        hc.setUsername(c.getString("network.database.user", "diamondcraft"));
        hc.setPassword(c.getString("network.database.password", ""));
        hc.setMaximumPoolSize(Math.max(2, c.getInt("network.database.pool-size", 6)));
        hc.setConnectionTimeout(8000);
        this.ds = new HikariDataSource(hc);
    }

    // ------------------------------------------------------------ basics
    public Connection conn() throws SQLException {
        return ds.getConnection();
    }

    public void async(Sql task) {
        try {
            writer.execute(() -> {
                try (Connection c = ds.getConnection()) {
                    task.run(c);
                } catch (SQLException e) {
                    plugin.getLogger().warning("Database error: " + e.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) { }
    }

    private static JsonObject parse(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private void schema() throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS dc_kv (ns VARCHAR(24) NOT NULL, k VARCHAR(96) NOT NULL, "
                    + "v MEDIUMTEXT NULL, deleted TINYINT(1) NOT NULL DEFAULT 0, updated BIGINT NOT NULL, "
                    + "PRIMARY KEY (ns, k), KEY idx_updated (updated)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS dc_balance (uuid CHAR(36) NOT NULL PRIMARY KEY, "
                    + "balance DOUBLE NOT NULL, updated BIGINT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS dc_counter (name VARCHAR(8) NOT NULL PRIMARY KEY, "
                    + "n BIGINT NOT NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        }
    }

    // ------------------------------------------------------------ key/value store
    private void upsert(Connection c, String ns, String key, String json) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO dc_kv (ns,k,v,deleted,updated) VALUES (?,?,?,0,"
                + NOW + ") ON DUPLICATE KEY UPDATE v=VALUES(v), deleted=0, updated=" + NOW)) {
            ps.setString(1, ns);
            ps.setString(2, key);
            ps.setString(3, json);
            ps.executeUpdate();
        }
    }

    public void put(String ns, String key, String json) {
        recent.put(ns + "|" + key, System.currentTimeMillis());
        async(c -> upsert(c, ns, key, json));
    }

    public void putIfAbsent(String ns, String key, String json) {
        async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT IGNORE INTO dc_kv (ns,k,v,deleted,updated) VALUES (?,?,?,0,"
                    + NOW + ")")) {
                ps.setString(1, ns);
                ps.setString(2, key);
                ps.setString(3, json);
                ps.executeUpdate();
            }
        });
    }

    public void remove(String ns, String key) {
        recent.put(ns + "|" + key, System.currentTimeMillis());
        async(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE dc_kv SET v=NULL, deleted=1, updated=" + NOW
                    + " WHERE ns=? AND k=?")) {
                ps.setString(1, ns);
                ps.setString(2, key);
                ps.executeUpdate();
            }
        });
    }

    /** Blocking read that REPORTS database errors (null = really not there). */
    public String read(String ns, String key) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT v FROM dc_kv WHERE ns=? AND k=? AND deleted=0")) {
            ps.setString(1, ns);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** Blocking read, use from async threads or rare actions only. Errors look like "not found". */
    public String get(String ns, String key) {
        try {
            return read(ns, key);
        } catch (SQLException e) {
            plugin.getLogger().warning("Database read failed: " + e.getMessage());
            return null;
        }
    }

    /** Writes, then runs onMain on the main thread (also if the write failed). */
    public void putThen(String ns, String key, String json, Runnable onMain) {
        recent.put(ns + "|" + key, System.currentTimeMillis());
        try {
            writer.execute(() -> {
                try (Connection c = ds.getConnection()) {
                    upsert(c, ns, key, json);
                } catch (SQLException e) {
                    plugin.getLogger().warning("Database error: " + e.getMessage());
                }
                Bukkit.getScheduler().runTask(plugin, onMain);
            });
        } catch (RejectedExecutionException ignored) { }
    }

    // ------------------------------------------------------------ counters (punishment IDs)
    /** Next number for a prefix (B, T, K). Returns -1 if the database fails. */
    public long nextId(String prefix) {
        try (Connection c = ds.getConnection()) {
            for (int i = 0; i < 3; i++) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE dc_counter SET n = LAST_INSERT_ID(n + 1) WHERE name = ?")) {
                    ps.setString(1, prefix);
                    if (ps.executeUpdate() > 0) {
                        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT LAST_INSERT_ID()")) {
                            if (rs.next()) return rs.getLong(1);
                        }
                    }
                }
                try (PreparedStatement ins = c.prepareStatement("INSERT IGNORE INTO dc_counter (name, n) VALUES (?, 1)")) {
                    ins.setString(1, prefix);
                    if (ins.executeUpdate() > 0) return 1;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Counter error: " + e.getMessage());
        }
        return -1;
    }

    public void raiseCounter(String prefix, long value) {
        async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO dc_counter (name, n) VALUES (?, ?) "
                    + "ON DUPLICATE KEY UPDATE n = GREATEST(n, VALUES(n))")) {
                ps.setString(1, prefix);
                ps.setLong(2, value);
                ps.executeUpdate();
            }
        });
    }

    // ------------------------------------------------------------ start / stop
    public void start() throws SQLException {
        schema();
        importLocalIfNeeded();
        pollNow(true);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, "BungeeCord");
        long period = Math.max(1, plugin.getConfig().getInt("network.sync-seconds", 3)) * 20L;
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> pollNow(false), period, period);
        Bukkit.getScheduler().runTaskTimer(plugin, this::heartbeat, 20L, 100L);
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::cleanup, 1200L, 12000L);
        plugin.economy.top(10);
        plugin.getLogger().info("Network mode ON: this server is '" + plugin.serverName()
                + "' and shares data through the database.");
    }

    public void shutdown() {
        writer.shutdown();
        try {
            writer.awaitTermination(8, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        ds.close();
    }

    private void importLocalIfNeeded() {
        File flag = new File(plugin.getDataFolder(), "network-imported.flag");
        if (flag.exists()) return;
        plugin.getLogger().info("Network: importing this server's local data into the shared database...");
        plugin.economy.exportLocal();
        plugin.homes.exportLocal();
        plugin.favorites.exportLocal();
        plugin.settings.exportLocal();
        plugin.discord.exportLocal();
        plugin.punish.exportLocal();
        plugin.history.exportLocal();
        plugin.players.exportLocal();
        try {
            writer.submit(() -> { }).get(30, TimeUnit.SECONDS);
            flag.createNewFile();
        } catch (Exception e) {
            plugin.getLogger().warning("Network import did not finish: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ sync loop
    private void pollNow(boolean initial) {
        if (!initial && polling) return;
        polling = true;
        try {
            List<Row> rows = new ArrayList<>();
            long max = since;
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement("SELECT ns,k,v,deleted,updated FROM dc_kv WHERE updated >= ?")) {
                ps.setLong(1, Math.max(0, since - 3000));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1));
                        max = Math.max(max, rs.getLong(5));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Network sync failed: " + e.getMessage());
                return;
            }
            since = max;
            Map<UUID, Double> bals = plugin.economy.fetch(onlineSnapshot);
            if (initial) applyAll(rows, bals);
            else Bukkit.getScheduler().runTask(plugin, () -> applyAll(rows, bals));
        } finally {
            polling = false;
        }
    }

    private void applyAll(List<Row> rows, Map<UUID, Double> bals) {
        for (Row r : rows) apply(r);
        plugin.economy.applyFetched(bals);
        plugin.punish.enforceOnline();
    }

    private void apply(Row r) {
        Long t = recent.get(r.ns() + "|" + r.key());
        if (t != null && System.currentTimeMillis() - t < 5000) return;
        try {
            switch (r.ns()) {
                case "home" -> plugin.homes.applyRemote(r.key(), r.value(), r.deleted());
                case "fav" -> plugin.favorites.applyRemote(r.key(), r.value(), r.deleted());
                case "set" -> plugin.settings.applyRemote(r.key(), r.value(), r.deleted());
                case "link", "rew", "rewd" -> plugin.discord.applyRemote(r.ns(), r.key(), r.value(), r.deleted());
                case "punish" -> plugin.punish.applyRemote(r.key(), r.value(), r.deleted());
                case "hist" -> plugin.history.applyRemote(r.key(), r.value(), r.deleted());
                case "name" -> plugin.players.applyRemote(r.key(), r.value(), r.deleted());
                case "tpa" -> plugin.tpa.applyRemote(r.key(), r.value(), r.deleted());
                case "online" -> applyOnline(r);
                case "note" -> applyNote(r);
                default -> { }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Network sync could not apply " + r.ns() + "/" + r.key() + ": " + e);
        }
    }

    private void heartbeat() {
        long now = System.currentTimeMillis();
        List<UUID> ids = new ArrayList<>();
        List<String[]> rows = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            ids.add(p.getUniqueId());
            JsonObject o = new JsonObject();
            o.addProperty("n", p.getName());
            o.addProperty("s", plugin.serverName());
            o.addProperty("t", now);
            rows.add(new String[]{p.getUniqueId().toString(), o.toString()});
        }
        onlineSnapshot = ids;
        recent.values().removeIf(t -> now - t > 15000);
        if (rows.isEmpty()) return;
        async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO dc_kv (ns,k,v,deleted,updated) VALUES ('online',?,?,0,"
                    + NOW + ") ON DUPLICATE KEY UPDATE v=VALUES(v), deleted=0, updated=" + NOW)) {
                for (String[] r : rows) {
                    ps.setString(1, r[0]);
                    ps.setString(2, r[1]);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        });
    }

    private void cleanup() {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("DELETE FROM dc_kv WHERE deleted=1 AND updated < " + NOW + " - 86400000");
            st.executeUpdate("DELETE FROM dc_kv WHERE ns IN ('online','tpa','tp','note','code') AND updated < "
                    + NOW + " - 3600000");
        } catch (SQLException e) {
            plugin.getLogger().warning("Cleanup failed: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ players on the network
    private void applyOnline(Row r) {
        UUID id = UUID.fromString(r.key());
        if (r.deleted() || r.value() == null) {
            online.remove(id);
            return;
        }
        JsonObject o = parse(r.value());
        if (System.currentTimeMillis() - o.get("t").getAsLong() > 30000) return;
        online.put(id, new OnlineInfo(id, o.get("n").getAsString(), o.get("s").getAsString(), System.currentTimeMillis()));
    }

    /** Players on OTHER servers of the network. */
    public List<OnlineInfo> remotePlayers() {
        long now = System.currentTimeMillis();
        online.values().removeIf(o -> now - o.seen() > 20000);
        List<OnlineInfo> out = new ArrayList<>();
        for (OnlineInfo o : online.values()) {
            if (!o.server().equalsIgnoreCase(plugin.serverName()) && Bukkit.getPlayer(o.id()) == null) out.add(o);
        }
        return out;
    }

    public OnlineInfo remoteById(UUID id) {
        for (OnlineInfo o : remotePlayers()) {
            if (o.id().equals(id)) return o;
        }
        return null;
    }

    public OnlineInfo findRemote(String name) {
        for (OnlineInfo o : remotePlayers()) {
            if (o.name().equalsIgnoreCase(name)) return o;
        }
        return null;
    }

    // ------------------------------------------------------------ messages to players on other servers
    public void notifyPlayer(UUID target, String key, String... kv) {
        JsonObject o = new JsonObject();
        o.addProperty("key", key);
        JsonArray a = new JsonArray();
        for (String s : kv) a.add(s);
        o.add("args", a);
        o.addProperty("exp", System.currentTimeMillis() + 60000);
        put("note", target + "_" + System.currentTimeMillis(), o.toString());
    }

    private void applyNote(Row r) {
        if (r.deleted() || r.value() == null || r.key().length() < 36) return;
        UUID target;
        try {
            target = UUID.fromString(r.key().substring(0, 36));
        } catch (IllegalArgumentException e) {
            return;
        }
        Player p = Bukkit.getPlayer(target);
        if (p == null) return;
        JsonObject o = parse(r.value());
        if (o.get("exp").getAsLong() < System.currentTimeMillis()) return;
        JsonArray a = o.getAsJsonArray("args");
        String[] kv = new String[a.size()];
        for (int i = 0; i < kv.length; i++) kv[i] = a.get(i).getAsString();
        plugin.msg.send(p, o.get("key").getAsString(), kv);
        remove("note", r.key());
    }

    // ------------------------------------------------------------ moving players between servers
    private void send(Player carrier, String... parts) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);
            for (String s : parts) out.writeUTF(s);
            carrier.sendPluginMessage(plugin, "BungeeCord", b.toByteArray());
        } catch (IOException ignored) { }
    }

    public void connect(Player p, String server) {
        plugin.msg.send(p, "tp-connecting", "server", server);
        send(p, "Connect", server);
    }

    public void connectOther(Player carrier, String playerName, String server) {
        send(carrier, "ConnectOther", playerName, server);
    }

    /** Teleport to one of your homes that is on another server. */
    public void sendToHome(Player p, HomeManager.Home h) {
        JsonObject o = new JsonObject();
        o.addProperty("server", h.server());
        o.addProperty("world", h.world());
        o.addProperty("x", h.x());
        o.addProperty("y", h.y());
        o.addProperty("z", h.z());
        o.addProperty("yaw", h.yaw());
        o.addProperty("pitch", h.pitch());
        o.addProperty("exp", System.currentTimeMillis() + 60000);
        putThen("tp", p.getUniqueId().toString(), o.toString(), () -> connect(p, h.server()));
    }

    /** Remember that mover must be teleported to target as soon as they arrive on destServer. */
    public void movePlayerToPlayer(UUID mover, String destServer, UUID target, Runnable thenOnMain) {
        JsonObject o = new JsonObject();
        o.addProperty("server", destServer);
        o.addProperty("target", target.toString());
        o.addProperty("exp", System.currentTimeMillis() + 60000);
        putThen("tp", mover.toString(), o.toString(), thenOnMain);
    }

    /** Called when a player joins this server: finishes a teleport that started on another server. */
    public void onJoin(Player p) {
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String v = get("tp", id.toString());
            if (v == null) return;
            JsonObject o = parse(v);
            if (o.get("exp").getAsLong() < System.currentTimeMillis()) {
                remove("tp", id.toString());
                return;
            }
            if (!o.get("server").getAsString().equalsIgnoreCase(plugin.serverName())) return;
            remove("tp", id.toString());
            Bukkit.getScheduler().runTaskLater(plugin, () -> finishTeleport(p, o), 10L);
        });
    }

    private void finishTeleport(Player p, JsonObject o) {
        if (!p.isOnline()) return;
        if (o.has("target")) {
            Player t = Bukkit.getPlayer(UUID.fromString(o.get("target").getAsString()));
            if (t == null) {
                plugin.msg.send(p, "tpa-offline");
                return;
            }
            p.teleportAsync(t.getLocation());
            plugin.msg.send(p, "tp-done", "target", t.getName());
            return;
        }
        World w = Bukkit.getWorld(o.get("world").getAsString());
        if (w == null) {
            plugin.msg.send(p, "rtp-no-world");
            return;
        }
        p.teleportAsync(new Location(w, o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble(),
                o.get("yaw").getAsFloat(), o.get("pitch").getAsFloat()));
        plugin.msg.send(p, "tp-done", "target", w.getName().toLowerCase(Locale.ROOT));
    }
}
