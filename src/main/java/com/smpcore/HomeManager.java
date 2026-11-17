package com.smpcore;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class HomeManager {
    /** A home: which server it is on and where. */
    public record Home(String server, String world, double x, double y, double z, float yaw, float pitch) {
        public Location toLocation() {
            World w = Bukkit.getWorld(world);
            return w == null ? null : new Location(w, x, y, z, yaw, pitch);
        }

        static Home of(String server, Location l) {
            return new Home(server, l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
        }

        JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("s", server);
            o.addProperty("w", world);
            o.addProperty("x", x);
            o.addProperty("y", y);
            o.addProperty("z", z);
            o.addProperty("a", yaw);
            o.addProperty("b", pitch);
            return o;
        }

        static Home fromJson(JsonObject o) {
            return new Home(o.get("s").getAsString(), o.get("w").getAsString(), o.get("x").getAsDouble(),
                    o.get("y").getAsDouble(), o.get("z").getAsDouble(), o.get("a").getAsFloat(), o.get("b").getAsFloat());
        }
    }

    private final SMPCore plugin;
    private final File file;
    private final Map<UUID, LinkedHashMap<String, Home>> data = new LinkedHashMap<>();
    private final Map<UUID, Integer> limitCache = new ConcurrentHashMap<>();

    public HomeManager(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "homes.yml");
        loadLocal();
    }

    /** Reload from the file (only in local mode; in network mode the database is the source). */
    public synchronized void load() {
        if (plugin.net == null) loadLocal();
    }

    private synchronized void loadLocal() {
        data.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        for (String key : yml.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            ConfigurationSection s = yml.getConfigurationSection(key);
            if (s == null) continue;
            LinkedHashMap<String, Home> map = new LinkedHashMap<>();
            for (String name : s.getKeys(false)) {
                Location l = s.getLocation(name);
                if (l != null && l.getWorld() != null) map.put(name, Home.of(plugin.serverName(), l));
            }
            if (!map.isEmpty()) data.put(id, map);
        }
    }

    private YamlConfiguration buildYaml() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, LinkedHashMap<String, Home>> e : data.entrySet()) {
            for (Map.Entry<String, Home> h : e.getValue().entrySet()) {
                Location l = h.getValue().toLocation();
                if (l != null) yml.set(e.getKey() + "." + h.getKey(), l);
            }
        }
        return yml;
    }

    private String json(UUID id) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Home> h : data.getOrDefault(id, new LinkedHashMap<>()).entrySet()) {
            o.add(h.getKey(), h.getValue().toJson());
        }
        return o.toString();
    }

    private void persist(UUID id) {
        if (plugin.net != null) {
            plugin.net.put("home", id.toString(), json(id));
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, this::saveLocal);
        }
    }

    /** Builds and writes the file under one lock, so the newest state is always what ends up on disk. */
    private synchronized void saveLocal() {
        if (plugin.net != null) return;
        try {
            buildYaml().save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save homes.yml: " + e.getMessage());
        }
    }

    /** Used on shutdown. */
    synchronized void saveNow() {
        saveLocal();
    }

    public synchronized Map<String, Home> all(UUID id) {
        return new LinkedHashMap<>(data.getOrDefault(id, new LinkedHashMap<>()));
    }

    public synchronized Home get(UUID id, String name) {
        LinkedHashMap<String, Home> m = data.get(id);
        return m == null ? null : m.get(name.toLowerCase());
    }

    public synchronized void set(UUID id, String name, Location loc) {
        data.computeIfAbsent(id, k -> new LinkedHashMap<>()).put(name.toLowerCase(), Home.of(plugin.serverName(), loc));
        persist(id);
    }

    public synchronized boolean delete(UUID id, String name) {
        LinkedHashMap<String, Home> m = data.get(id);
        if (m == null || m.remove(name.toLowerCase()) == null) return false;
        persist(id);
        return true;
    }

    // ------------------------------------------------------------ network sync
    public synchronized void applyRemote(String key, String value, boolean deleted) {
        UUID id = UUID.fromString(key);
        if (deleted || value == null) {
            data.remove(id);
            return;
        }
        JsonObject o = JsonParser.parseString(value).getAsJsonObject();
        LinkedHashMap<String, Home> m = new LinkedHashMap<>();
        for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
            m.put(e.getKey(), Home.fromJson(e.getValue().getAsJsonObject()));
        }
        data.put(id, m);
    }

    public synchronized void exportLocal() {
        for (UUID id : data.keySet()) plugin.net.putIfAbsent("home", id.toString(), json(id));
    }

    // ------------------------------------------------------------ limits
    /** Last known limit (safe from any thread, used by placeholders). */
    public int cachedLimit(UUID id) {
        return limitCache.getOrDefault(id, plugin.getConfig().getInt("homes.default-limit", 1));
    }

    public int limit(Player p) {
        if (!Bukkit.isPrimaryThread()) return cachedLimit(p.getUniqueId());
        int v = computeLimit(p);
        limitCache.put(p.getUniqueId(), v);
        return v;
    }

    private int computeLimit(Player p) {
        if (p.hasPermission("smpcore.homes.unlimited")) return Integer.MAX_VALUE;
        int best = plugin.getConfig().getInt("homes.default-limit", 1);
        ConfigurationSection groups = plugin.getConfig().getConfigurationSection("homes.group-limits");
        if (groups != null) {
            for (String g : groups.getKeys(false)) {
                if (p.hasPermission("group." + g)) best = Math.max(best, groups.getInt(g));
            }
        }
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            String perm = info.getPermission();
            if (info.getValue() && perm.startsWith("smpcore.homes.")) {
                try {
                    best = Math.max(best, Integer.parseInt(perm.substring("smpcore.homes.".length())));
                } catch (NumberFormatException ignored) { }
            }
        }
        return best;
    }

    public String limitText(Player p) {
        int l = limit(p);
        return l > 100000 ? "\u221e" : String.valueOf(l);
    }
}
