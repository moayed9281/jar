package com.smpcore;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PunishManager {
    public enum Type { BAN, TIMEOUT }

    public record Rec(Type type, UUID id, String name, long until, String reason, String staff) { }

    private final SMPCore plugin;
    private final File file;
    private final Map<String, Rec> map = new ConcurrentHashMap<>();

    public PunishManager(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "punishments.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = yml.getConfigurationSection("entries");
        if (s != null) {
            long now = System.currentTimeMillis();
            for (String key : s.getKeys(false)) {
                ConfigurationSection e = s.getConfigurationSection(key);
                if (e == null) continue;
                try {
                    Rec r = new Rec(Type.valueOf(e.getString("type")), UUID.fromString(e.getString("uuid")),
                            e.getString("name", "?"), e.getLong("until"), e.getString("reason", ""),
                            e.getString("staff", "Console"));
                    if (r.until() == 0 || r.until() > now) map.put(key(r.id(), r.type()), r);
                } catch (Exception ignored) { }
            }
        }
    }

    private static String key(UUID id, Type t) {
        return id + "_" + t;
    }

    /** Safe to call from the async login thread. */
    public Rec get(UUID id, Type t) {
        Rec r = map.get(key(id, t));
        if (r == null) return null;
        if (r.until() != 0 && r.until() < System.currentTimeMillis()) return null;
        return r;
    }

    public void add(Rec r) {
        map.put(key(r.id(), r.type()), r);
        save();
    }

    public boolean remove(UUID id, Type t) {
        boolean had = map.remove(key(id, t)) != null;
        if (had) save();
        return had;
    }

    private synchronized void save() {
        YamlConfiguration yml = new YamlConfiguration();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Rec> en : map.entrySet()) {
            Rec r = en.getValue();
            if (r.until() != 0 && r.until() < now) continue;
            String p = "entries." + en.getKey() + ".";
            yml.set(p + "type", r.type().name());
            yml.set(p + "uuid", r.id().toString());
            yml.set(p + "name", r.name());
            yml.set(p + "until", r.until());
            yml.set(p + "reason", r.reason());
            yml.set(p + "staff", r.staff());
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save punishments.yml: " + e.getMessage());
        }
    }

    private static String strip(String n) {
        int i = 0;
        while (i < n.length() && !Character.isLetterOrDigit(n.charAt(i)) && n.charAt(i) != '_') i++;
        return n.substring(i);
    }

    /** Remove by stored name (case-insensitive, ignores Bedrock prefix). */
    public boolean removeByName(String name, Type t) {
        for (Rec r : map.values()) {
            if (r.type() == t && (r.name().equalsIgnoreCase(name)
                    || strip(r.name()).equalsIgnoreCase(strip(name)))) {
                return remove(r.id(), t);
            }
        }
        return false;
    }

    public java.util.List<String> names(Type t) {
        java.util.List<String> out = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        for (Rec r : map.values()) {
            if (r.type() == t && (r.until() == 0 || r.until() > now)) out.add(r.name());
        }
        return out;
    }
}
