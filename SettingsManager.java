package com.smpcore;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-player toggles shown in the Settings menu. */
public final class SettingsManager {
    private final SMPCore plugin;
    private final File file;
    private final Set<UUID> nightVision = ConcurrentHashMap.newKeySet();
    private final Set<UUID> noTpa = ConcurrentHashMap.newKeySet();
    private final Set<UUID> noTpaHere = ConcurrentHashMap.newKeySet();

    public SettingsManager(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "settings.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        read(yml.getStringList("night-vision"), nightVision);
        read(yml.getStringList("no-tpa"), noTpa);
        // older versions had ONE switch for both: keep what players chose
        if (yml.contains("no-tpahere")) read(yml.getStringList("no-tpahere"), noTpaHere);
        else noTpaHere.addAll(noTpa);
    }

    private void read(List<String> in, Set<UUID> out) {
        for (String s : in) {
            try {
                out.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) { }
        }
    }

    synchronized void save() {
        if (plugin.net != null) return;
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("night-vision", nightVision.stream().map(UUID::toString).toList());
        yml.set("no-tpa", noTpa.stream().map(UUID::toString).toList());
        yml.set("no-tpahere", noTpaHere.stream().map(UUID::toString).toList());
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save settings.yml: " + e.getMessage());
        }
    }

    public boolean nightVision(UUID id) {
        return nightVision.contains(id);
    }

    public boolean acceptsTpa(UUID id) {
        return !noTpa.contains(id);
    }

    public boolean acceptsTpaHere(UUID id) {
        return !noTpaHere.contains(id);
    }

    /** here = false: /tpa requests (someone wants to come to you), true: /tpahere requests (someone wants you to come to them). */
    public boolean accepts(UUID id, boolean here) {
        return here ? acceptsTpaHere(id) : acceptsTpa(id);
    }

    public void toggleTpa(UUID id) {
        if (!noTpa.remove(id)) noTpa.add(id);
        persist(id);
    }

    public void toggleTpaHere(UUID id) {
        if (!noTpaHere.remove(id)) noTpaHere.add(id);
        persist(id);
    }

    public void toggleNightVision(Player p) {
        if (!nightVision.remove(p.getUniqueId())) nightVision.add(p.getUniqueId());
        if (nightVision.contains(p.getUniqueId())) applyNightVision(p);
        else p.removePotionEffect(PotionEffectType.NIGHT_VISION);
        persist(p.getUniqueId());
    }

    /** Adds the effect if the player turned it on (used on join, respawn and toggle). */
    public void applyNightVision(Player p) {
        boolean allowed = plugin.getConfig().getBoolean("settings.night-vision", true);
        if (allowed && nightVision.contains(p.getUniqueId())) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, PotionEffect.INFINITE_DURATION,
                    0, false, false, false));
        }
    }

    private String json(UUID id) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("nv", nightVision.contains(id));
        o.addProperty("nt", noTpa.contains(id));
        o.addProperty("nh", noTpaHere.contains(id));
        return o.toString();
    }

    private void persist(UUID id) {
        if (plugin.net != null) plugin.net.put("set", id.toString(), json(id));
        else Bukkit.getScheduler().runTaskAsynchronously(plugin, this::save);
    }

    public void applyRemote(String key, String value, boolean deleted) {
        if (deleted || value == null) return;
        UUID id = UUID.fromString(key);
        com.google.gson.JsonObject o = new com.google.gson.JsonParser().parse(value).getAsJsonObject();
        boolean nv = o.get("nv").getAsBoolean();
        if (nv) nightVision.add(id);
        else nightVision.remove(id);
        boolean nt = o.get("nt").getAsBoolean();
        if (nt) noTpa.add(id);
        else noTpa.remove(id);
        // rows written by an older version have no "nh": they meant both
        if (o.has("nh") ? o.get("nh").getAsBoolean() : nt) noTpaHere.add(id);
        else noTpaHere.remove(id);
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            if (nv) applyNightVision(p);
            else p.removePotionEffect(PotionEffectType.NIGHT_VISION);
        }
    }

    public void exportLocal() {
        java.util.Set<UUID> all = new java.util.HashSet<>(nightVision);
        all.addAll(noTpa);
        all.addAll(noTpaHere);
        for (UUID id : all) plugin.net.putIfAbsent("set", id.toString(), json(id));
    }
}
