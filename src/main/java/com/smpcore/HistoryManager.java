package com.smpcore;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Permanent log of every ban, timeout and kick with a unique ID (B-12, T-5, K-30). */
public final class HistoryManager {
    public static final class Entry {
        public final String id;
        public final PunishManager.Type type;
        public final UUID uuid;
        public final String name;
        public final String staff;
        public final String reason;
        public final long created;
        public final long until;
        public volatile String liftedBy = "";
        public volatile long liftedAt;

        Entry(String id, PunishManager.Type type, UUID uuid, String name, String staff, String reason,
              long created, long until) {
            this.id = id;
            this.type = type;
            this.uuid = uuid;
            this.name = name;
            this.staff = staff;
            this.reason = reason;
            this.created = created;
            this.until = until;
        }

        public boolean active() {
            return type != PunishManager.Type.KICK && liftedAt == 0
                    && (until == 0 || until > System.currentTimeMillis());
        }
    }

    private final SMPCore plugin;
    private final File file;
    private final LinkedHashMap<String, Entry> map = new LinkedHashMap<>();
    private final Map<String, Integer> counters = new HashMap<>();

    public HistoryManager(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "history.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection c = yml.getConfigurationSection("counters");
        if (c != null) for (String k : c.getKeys(false)) counters.put(k, c.getInt(k));
        ConfigurationSection s = yml.getConfigurationSection("entries");
        if (s == null) return;
        for (String id : s.getKeys(false)) {
            ConfigurationSection e = s.getConfigurationSection(id);
            if (e == null) continue;
            try {
                Entry en = new Entry(id, PunishManager.Type.valueOf(e.getString("type")),
                        UUID.fromString(e.getString("uuid")), e.getString("name", "?"),
                        e.getString("staff", "Console"), e.getString("reason", ""),
                        e.getLong("created"), e.getLong("until"));
                en.liftedBy = e.getString("lifted-by", "");
                en.liftedAt = e.getLong("lifted-at");
                map.put(id, en);
            } catch (Exception ignored) { }
        }
    }

    private static String prefix(PunishManager.Type t) {
        return switch (t) {
            case BAN -> "B";
            case TIMEOUT -> "T";
            case KICK -> "K";
        };
    }

    public Entry create(PunishManager.Type type, UUID uuid, String name, String staff, String reason, long until) {
        String p = prefix(type);
        long n = plugin.net != null ? plugin.net.nextId(p) : -1;
        synchronized (this) {
            String id;
            if (plugin.net != null && n < 0) {
                // counter unavailable: never reuse an ID (that would overwrite another log entry)
                id = p + "-" + Long.toString(System.currentTimeMillis(), 36).toUpperCase(Locale.ROOT);
            } else {
                if (n < 0) n = counters.merge(p, 1, Integer::sum);
                id = p + "-" + n;
            }
            Entry e = new Entry(id, type, uuid, name, staff, reason, System.currentTimeMillis(), until);
            map.put(e.id, e);
            persist(e);
            plugin.discord.logPunishment(e, false);
            return e;
        }
    }

    /** Accepts B-12, b12, B 12. */
    public synchronized Entry get(String raw) {
        String s = raw.toUpperCase(Locale.ROOT).replace(" ", "");
        if (s.matches("[BTK]\\d+")) s = s.charAt(0) + "-" + s.substring(1);
        return map.get(s);
    }

    private List<Entry> newestFirst() {
        List<Entry> l = new ArrayList<>(map.values());
        l.sort(Comparator.comparingLong((Entry e) -> e.created).reversed());
        return l;
    }

    public synchronized List<Entry> all() {
        return newestFirst();
    }

    public synchronized List<Entry> ofType(PunishManager.Type t) {
        return newestFirst().stream().filter(e -> e.type == t).toList();
    }

    public synchronized List<Entry> active(PunishManager.Type t) {
        return newestFirst().stream().filter(e -> e.type == t && e.active()).toList();
    }

    public synchronized int activeCount(PunishManager.Type t) {
        return (int) map.values().stream().filter(e -> e.type == t && e.active()).count();
    }

    public synchronized List<Entry> byPlayer(UUID id, String name) {
        return newestFirst().stream()
                .filter(e -> e.uuid.equals(id) || strip(e.name).equalsIgnoreCase(strip(name))).toList();
    }

    private static String strip(String n) {
        int i = 0;
        while (i < n.length() && !Character.isLetterOrDigit(n.charAt(i)) && n.charAt(i) != '_') i++;
        return n.substring(i);
    }

    public synchronized void lift(Entry e, String by) {
        e.liftedBy = by;
        e.liftedAt = System.currentTimeMillis();
        persist(e);
        if (!by.startsWith("(replaced")) plugin.discord.logPunishment(e, true);
    }

    public synchronized void liftActiveById(UUID id, PunishManager.Type t, String by) {
        for (Entry e : new ArrayList<>(map.values())) {
            if (e.type == t && e.uuid.equals(id) && e.active()) lift(e, by);
        }
    }

    public synchronized void liftActiveByName(String name, PunishManager.Type t, String by) {
        for (Entry e : new ArrayList<>(map.values())) {
            if (e.type == t && e.active() && strip(e.name).equalsIgnoreCase(strip(name))) lift(e, by);
        }
    }

    // ------------------------------------------------------------ saving
    private static String toJson(Entry e) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id);
        o.addProperty("t", e.type.name());
        o.addProperty("u", e.uuid.toString());
        o.addProperty("n", e.name);
        o.addProperty("s", e.staff);
        o.addProperty("r", e.reason);
        o.addProperty("c", e.created);
        o.addProperty("x", e.until);
        o.addProperty("lb", e.liftedBy);
        o.addProperty("la", e.liftedAt);
        return o.toString();
    }

    private void persist(Entry e) {
        if (plugin.net != null) plugin.net.put("hist", e.id, toJson(e));
        else saveLater();
    }

    private void saveLater() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::save);
    }

    synchronized void save() {
        if (plugin.net != null) return;
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<String, Integer> c : counters.entrySet()) yml.set("counters." + c.getKey(), c.getValue());
        for (Entry e : map.values()) {
            String p = "entries." + e.id + ".";
            yml.set(p + "type", e.type.name());
            yml.set(p + "uuid", e.uuid.toString());
            yml.set(p + "name", e.name);
            yml.set(p + "staff", e.staff);
            yml.set(p + "reason", e.reason);
            yml.set(p + "created", e.created);
            yml.set(p + "until", e.until);
            yml.set(p + "lifted-by", e.liftedBy);
            yml.set(p + "lifted-at", e.liftedAt);
        }
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save history.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ network
    public synchronized void applyRemote(String key, String value, boolean deleted) {
        if (deleted || value == null) {
            map.remove(key);
            return;
        }
        JsonObject o = new JsonParser().parse(value).getAsJsonObject();
        Entry ex = map.get(key);
        if (ex != null) {
            long before = ex.liftedAt;
            ex.liftedBy = o.get("lb").getAsString();
            ex.liftedAt = o.get("la").getAsLong();
            if (before == 0 && ex.liftedAt > 0 && recent(ex.liftedAt) && !ex.liftedBy.startsWith("(replaced")) {
                plugin.discord.logPunishment(ex, true);
            }
            return;
        }
        Entry en = new Entry(o.get("id").getAsString(), PunishManager.Type.valueOf(o.get("t").getAsString()),
                UUID.fromString(o.get("u").getAsString()), o.get("n").getAsString(), o.get("s").getAsString(),
                o.get("r").getAsString(), o.get("c").getAsLong(), o.get("x").getAsLong());
        en.liftedBy = o.get("lb").getAsString();
        en.liftedAt = o.get("la").getAsLong();
        map.put(en.id, en);
        if (recent(en.created)) plugin.discord.logPunishment(en, false);
    }

    /** Only things that just happened are posted (old history loaded at startup is not). */
    private static boolean recent(long t) {
        return System.currentTimeMillis() - t < 120_000L;
    }

    public synchronized void exportLocal() {
        for (Entry e : map.values()) plugin.net.putIfAbsent("hist", e.id, toJson(e));
        for (Map.Entry<String, Integer> c : counters.entrySet()) plugin.net.raiseCounter(c.getKey(), c.getValue());
    }
}
