package com.smpcore;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Each player's favourite friends for quick /tpa and /tpahere. */
public final class FavoritesManager {
    private final SMPCore plugin;
    private final File file;
    private final Map<UUID, LinkedHashSet<UUID>> map = new HashMap<>();

    public FavoritesManager(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "favorites.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = yml.getConfigurationSection("favorites");
        if (s == null) return;
        for (String key : s.getKeys(false)) {
            try {
                LinkedHashSet<UUID> set = new LinkedHashSet<>();
                for (String v : s.getStringList(key)) set.add(UUID.fromString(v));
                map.put(UUID.fromString(key), set);
            } catch (IllegalArgumentException ignored) { }
        }
    }

    public synchronized List<UUID> list(UUID owner) {
        return new ArrayList<>(map.getOrDefault(owner, new LinkedHashSet<>()));
    }

    public synchronized boolean has(UUID owner, UUID other) {
        LinkedHashSet<UUID> set = map.get(owner);
        return set != null && set.contains(other);
    }

    /** 0 = added, 1 = already a favourite, 2 = list is full. */
    public synchronized int add(UUID owner, UUID other, int max) {
        LinkedHashSet<UUID> set = map.computeIfAbsent(owner, k -> new LinkedHashSet<>());
        if (set.contains(other)) return 1;
        if (set.size() >= max) return 2;
        set.add(other);
        persist(owner);
        return 0;
    }

    public synchronized boolean remove(UUID owner, UUID other) {
        LinkedHashSet<UUID> set = map.get(owner);
        boolean removed = set != null && set.remove(other);
        if (removed) persist(owner);
        return removed;
    }

    private void saveLater() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::save);
    }

    synchronized void save() {
        if (plugin.net != null) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, LinkedHashSet<UUID>> e : map.entrySet()) {
            yml.set("favorites." + e.getKey(), e.getValue().stream().map(UUID::toString).toList());
        }
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save favorites.yml: " + ex.getMessage());
        }
    }

    private String json(UUID owner) {
        com.google.gson.JsonArray a = new com.google.gson.JsonArray();
        for (UUID u : map.getOrDefault(owner, new LinkedHashSet<>())) a.add(u.toString());
        return a.toString();
    }

    private void persist(UUID owner) {
        if (plugin.net != null) plugin.net.put("fav", owner.toString(), json(owner));
        else saveLater();
    }

    public synchronized void applyRemote(String key, String value, boolean deleted) {
        UUID owner = UUID.fromString(key);
        if (deleted || value == null) {
            map.remove(owner);
            return;
        }
        LinkedHashSet<UUID> set = new LinkedHashSet<>();
        for (com.google.gson.JsonElement e : com.google.gson.JsonParser.parseString(value).getAsJsonArray()) {
            set.add(UUID.fromString(e.getAsString()));
        }
        map.put(owner, set);
    }

    public synchronized void exportLocal() {
        for (UUID owner : map.keySet()) plugin.net.putIfAbsent("fav", owner.toString(), json(owner));
    }
}
