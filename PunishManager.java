package com.smpcore;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PunishManager {
    public enum Type { BAN, TIMEOUT, KICK, MUTE }

    public record Rec(Type type, UUID id, String name, long until, String reason, String staff, String punishId) { }

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
                            e.getString("staff", "Console"), e.getString("pid", ""));
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

    /** Active record for the UUID, or (cracked servers) for the same name in any upper/lower case. */
    public Rec find(UUID id, String name, Type t) {
        Rec r = get(id, t);
        if (r != null) return r;
        if (name == null || !plugin.getConfig().getBoolean("punishments.match-names", true)) return null;
        long now = System.currentTimeMillis();
        for (Rec x : map.values()) {
            if (x.type() == t && x.name().equalsIgnoreCase(name) && (x.until() == 0 || x.until() > now)) return x;
        }
        return null;
    }

    public void add(Rec r) {
        String k = key(r.id(), r.type());
        map.put(k, r);
        if (plugin.net != null) plugin.net.put("punish", k, toJson(r));
        else save();
    }

    public boolean remove(UUID id, Type t) {
        String k = key(id, t);
        boolean had = map.remove(k) != null;
        if (plugin.net != null) plugin.net.remove("punish", k);
        else if (had) save();
        return had;
    }

    private static String strip(String n) {
        int i = 0;
        while (i < n.length() && !Character.isLetterOrDigit(n.charAt(i)) && n.charAt(i) != '_') i++;
        return n.substring(i);
    }

    /** Remove by stored name. Exact name first; the Bedrock-prefix-insensitive match only when it is unambiguous. */
    public Rec removeByName(String name, Type t) {
        for (Rec r : map.values()) {
            if (r.type() == t && r.name().equalsIgnoreCase(name)) {
                remove(r.id(), t);
                return r;
            }
        }
        Rec only = null;
        int count = 0;
        for (Rec r : map.values()) {
            if (r.type() == t && strip(r.name()).equalsIgnoreCase(strip(name))) {
                only = r;
                count++;
            }
        }
        if (count != 1) return null;
        remove(only.id(), t);
        return only;
    }

    /** Is at least one record of this type active right now? */
    public boolean anyActive(Type t) {
        long now = System.currentTimeMillis();
        for (Rec r : map.values()) {
            if (r.type() == t && (r.until() == 0 || r.until() > now)) return true;
        }
        return false;
    }

    public List<String> names(Type t) {
        List<String> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Rec r : map.values()) {
            if (r.type() == t && (r.until() == 0 || r.until() > now)) out.add(r.name());
        }
        return out;
    }

    // ------------------------------------------------------------ network
    private static String toJson(Rec r) {
        JsonObject o = new JsonObject();
        o.addProperty("t", r.type().name());
        o.addProperty("u", r.id().toString());
        o.addProperty("n", r.name());
        o.addProperty("x", r.until());
        o.addProperty("r", r.reason());
        o.addProperty("s", r.staff());
        o.addProperty("p", r.punishId() == null ? "" : r.punishId());
        return o.toString();
    }

    private static Rec fromJson(String json) {
        JsonObject o = new JsonParser().parse(json).getAsJsonObject();
        return new Rec(Type.valueOf(o.get("t").getAsString()), UUID.fromString(o.get("u").getAsString()),
                o.get("n").getAsString(), o.get("x").getAsLong(), o.get("r").getAsString(),
                o.get("s").getAsString(), o.get("p").getAsString());
    }

    public void applyRemote(String key, String value, boolean deleted) {
        if (deleted || value == null) map.remove(key);
        else map.put(key, fromJson(value));
    }

    /** Login (async thread): read the newest ban/timeout of this player straight from the database. */
    public void refresh(UUID id) {
        for (Type t : new Type[]{Type.BAN, Type.TIMEOUT, Type.MUTE}) {
            String k = key(id, t);
            try {
                String v = plugin.net.read("punish", k);
                if (v == null) map.remove(k);
                else map.put(k, fromJson(v));
            } catch (Exception ex) {
                // database problem: keep what we already know, never drop a ban because of an error
            }
        }
    }

    /** Main thread: kick online players that were banned or timed out on another server. */
    public void enforceOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Rec r = find(p.getUniqueId(), p.getName(), Type.BAN);
            String screen = "ban";
            if (r == null) {
                r = find(p.getUniqueId(), p.getName(), Type.TIMEOUT);
                screen = "timeout";
            }
            if (r != null) p.kick(plugin.msg.screen(screen, r));
        }
    }

    public void exportLocal() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Rec> e : map.entrySet()) {
            Rec r = e.getValue();
            if (r.until() == 0 || r.until() > now) plugin.net.putIfAbsent("punish", e.getKey(), toJson(r));
        }
    }

    synchronized void save() {
        if (plugin.net != null) return;
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
            yml.set(p + "pid", r.punishId());
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save punishments.yml: " + e.getMessage());
        }
    }
}
