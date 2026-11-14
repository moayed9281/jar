package com.smpcore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
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
    private final SMPCore plugin;
    private final File file;
    private YamlConfiguration yml;
    private final Map<UUID, Integer> limitCache = new ConcurrentHashMap<>();

    public HomeManager(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "homes.yml");
        load();
    }

    public synchronized void load() {
        yml = YamlConfiguration.loadConfiguration(file);
    }

    private synchronized void save() {
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save homes.yml: " + e.getMessage());
        }
    }

    public synchronized Map<String, Location> all(UUID id) {
        Map<String, Location> out = new LinkedHashMap<>();
        ConfigurationSection s = yml.getConfigurationSection(id.toString());
        if (s == null) return out;
        for (String key : s.getKeys(false)) {
            Location l = s.getLocation(key);
            if (l != null && l.getWorld() != null) out.put(key, l);
        }
        return out;
    }

    public synchronized Location get(UUID id, String name) {
        return all(id).get(name.toLowerCase());
    }

    public synchronized void set(UUID id, String name, Location loc) {
        yml.set(id + "." + name.toLowerCase(), loc);
        save();
    }

    public synchronized boolean delete(UUID id, String name) {
        String path = id + "." + name.toLowerCase();
        if (!yml.contains(path)) return false;
        yml.set(path, null);
        save();
        return true;
    }

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
        return l > 100000 ? "∞" : String.valueOf(l);
    }
}
