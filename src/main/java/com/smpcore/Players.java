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
        }
    }

    private synchronized void save() {
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
