package com.smpcore;

import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Remembers every name that tried to join and resolves names (Java + Bedrock) for offline punishments. */
public final class Players {
    public record Resolved(UUID id, String name) { }

    private final SMPCore plugin;
    private final File file;
    private final Map<String, Resolved> byName = new ConcurrentHashMap<>();

    public Players(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "players.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = yml.getConfigurationSection("players");
        if (s != null) {
            for (String key : s.getKeys(false)) {
                try {
                    byName.put(key, new Resolved(UUID.fromString(s.getString(key + ".uuid")),
                            s.getString(key + ".name", key)));
                } catch (Exception ignored) { }
            }
        }
    }

    /** Safe from any thread. */
    public void record(String name, UUID id) {
        Resolved old = byName.put(name.toLowerCase(), new Resolved(id, name));
        if (old == null || !old.id().equals(id) || !old.name().equals(name)) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, this::save);
            if (plugin.net != null) plugin.net.put("name", name.toLowerCase(), nameJson(id, name));
        }
    }

    private static String nameJson(UUID id, String name) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("i", id.toString());
        o.addProperty("n", name);
        return o.toString();
    }

    public void applyRemote(String key, String value, boolean deleted) {
        if (deleted || value == null) return;
        com.google.gson.JsonObject o = new com.google.gson.JsonParser().parse(value).getAsJsonObject();
        byName.put(key, new Resolved(UUID.fromString(o.get("i").getAsString()), o.get("n").getAsString()));
    }

    public void exportLocal() {
        for (Map.Entry<String, Resolved> e : byName.entrySet()) {
            plugin.net.putIfAbsent("name", e.getKey(), nameJson(e.getValue().id(), e.getValue().name()));
        }
    }

    synchronized void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<String, Resolved> e : byName.entrySet()) {
            yml.set("players." + e.getKey() + ".uuid", e.getValue().id().toString());
            yml.set("players." + e.getKey() + ".name", e.getValue().name());
        }
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save players.yml: " + ex.getMessage());
        }
    }

    private void sync(Consumer<Resolved> cb, Resolved r) {
        Bukkit.getScheduler().runTask(plugin, () -> cb.accept(r));
    }

    /** UUID for a name without going async: online, cached, else a plain Bukkit lookup. */
    public UUID idFor(String name) {
        Player on = Bukkit.getPlayerExact(name);
        if (on != null) return on.getUniqueId();
        Resolved r = byName.get(name.toLowerCase());
        if (r != null) return r.id();
        return Bukkit.getOfflinePlayer(name).getUniqueId();
    }

    public String nameFor(UUID id) {
        Player on = Bukkit.getPlayer(id);
        if (on != null) return on.getName();
        for (Resolved r : byName.values()) if (r.id().equals(id)) return r.name();
        return id.toString().substring(0, 8);
    }

    /**
     * Like resolve, but only online players, remembered names, or anyone who ever joined this server
     * (even before the plugin was installed). Never calls Mojang (safe for normal players' commands).
     */
    public void resolveKnown(String input, Consumer<Resolved> cb) {
        Player on = Bukkit.getPlayerExact(input);
        if (on != null) {
            cb.accept(new Resolved(on.getUniqueId(), on.getName()));
            return;
        }
        Resolved cached = byName.get(input.toLowerCase());
        FloodgateHook fg = plugin.floodgate;
        String prefix = fg == null ? "" : fg.prefix();
        if (cached == null && !prefix.isEmpty() && !input.startsWith(prefix)) {
            cached = byName.get((prefix + input).toLowerCase());
        }
        if (cached == null) cached = playedBefore(input, prefix);
        cb.accept(cached);
    }

    /** Looks through the server's player data for someone who joined before. Main thread only. */
    private Resolved playedBefore(String input, String prefix) {
        for (OfflinePlayer o : Bukkit.getOfflinePlayers()) {
            String n = o.getName();
            if (n == null) continue;
            if (n.equalsIgnoreCase(input) || (!prefix.isEmpty() && n.equalsIgnoreCase(prefix + input))) {
                Resolved r = new Resolved(o.getUniqueId(), n);
                byName.put(n.toLowerCase(), r);
                return r;
            }
        }
        return null;
    }

    /** Everyone who ever joined (remembered names + the server's player data), sorted by name. Main thread only. */
    public java.util.List<Resolved> allKnown() {
        Map<UUID, Resolved> all = new java.util.HashMap<>();
        for (Resolved r : byName.values()) all.put(r.id(), r);
        for (OfflinePlayer o : Bukkit.getOfflinePlayers()) {
            if (o.getName() != null) all.putIfAbsent(o.getUniqueId(), new Resolved(o.getUniqueId(), o.getName()));
        }
        java.util.List<Resolved> list = new java.util.ArrayList<>(all.values());
        list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return list;
    }

    /** Calls cb on the main thread; null means the player could not be found. */
    public void resolve(String input, Consumer<Resolved> cb) {
        Player on = Bukkit.getPlayerExact(input);
        if (on != null) {
            cb.accept(new Resolved(on.getUniqueId(), on.getName()));
            return;
        }
        Resolved cached = byName.get(input.toLowerCase());
        FloodgateHook fg = plugin.floodgate;
        String prefix = fg == null ? "" : fg.prefix();
        if (cached == null && !prefix.isEmpty() && !input.startsWith(prefix)) {
            cached = byName.get((prefix + input).toLowerCase());
        }
        if (cached != null) {
            cb.accept(cached);
            return;
        }
        if (!prefix.isEmpty() && input.startsWith(prefix) && input.length() > prefix.length()) {
            try {
                fg.uuidFor(input.substring(prefix.length()), uuid ->
                        sync(cb, uuid == null ? null : new Resolved(uuid, input)));
            } catch (Throwable t) {
                cb.accept(null);
            }
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> sync(cb, lookupJava(input)));
    }

    private Resolved lookupJava(String name) {
        try {
            if (!Bukkit.getOnlineMode()) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(name);
                return new Resolved(op.getUniqueId(), op.getName() != null ? op.getName() : name);
            }
            PlayerProfile pp = Bukkit.createProfile(name);
            if (pp.complete(false) && pp.getId() != null) {
                return new Resolved(pp.getId(), pp.getName() != null ? pp.getName() : name);
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
