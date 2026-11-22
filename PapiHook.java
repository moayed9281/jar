package com.smpcore;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** %diamondcraft_...% placeholders for TAB, scoreboards, holograms, DeluxeHub... Only loaded with PlaceholderAPI. */
final class PapiHook extends PlaceholderExpansion {
    private final SMPCore plugin;

    PapiHook(SMPCore plugin) {
        this.plugin = plugin;
    }

    @Override public String getIdentifier() { return "diamondcraft"; }
    @Override public String getAuthor() { return "DiamondCraft"; }
    @Override public String getVersion() { return "1.0.0"; }
    @Override public boolean persist() { return true; }

    private String shortMoney(double v) {
        String sym = plugin.getConfig().getString("economy.symbol", "$");
        if (v >= 1e12) return sym + trim(v / 1e12) + "T";
        if (v >= 1e9) return sym + trim(v / 1e9) + "B";
        if (v >= 1e6) return sym + trim(v / 1e6) + "M";
        if (v >= 1e3) return sym + trim(v / 1e3) + "K";
        return plugin.economy.format(v);
    }

    private String trim(double v) {
        String s = String.format(Locale.US, "%.1f", v);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    @Override
    public String onRequest(OfflinePlayer op, String params) {
        try {
            String k = params.toLowerCase(Locale.ROOT);
            // %diamondcraft_baltop_1_name%  %diamondcraft_baltop_1_balance%
            if (k.startsWith("baltop_")) {
                String[] x = k.split("_");
                if (x.length != 3) return null;
                int n = Integer.parseInt(x[1]);
                List<Map.Entry<UUID, Double>> top = plugin.economy.top(Math.max(1, n));
                if (n < 1 || top.size() < n) return "-";
                Map.Entry<UUID, Double> e = top.get(n - 1);
                return x[2].equals("name") ? plugin.players.nameFor(e.getKey()) : plugin.economy.format(e.getValue());
            }
            if (op == null) return "";
            UUID id = op.getUniqueId();
            Player on = op.getPlayer();
            boolean econ = plugin.economy.enabled();
            int used = plugin.homes.all(id).size();
            int limit = plugin.homes.cachedLimit(id);
            if (on != null && Bukkit.isPrimaryThread()) limit = plugin.homes.limit(on);
            boolean unlimited = limit > 100000;
            return switch (k) {
                case "balance" -> econ ? plugin.economy.format(plugin.economy.get(id)) : "";
                case "balance_short" -> econ ? shortMoney(plugin.economy.get(id)) : "";
                case "balance_raw" -> econ ? String.format(Locale.US, "%.2f", plugin.economy.get(id)) : "";
                case "homes" -> String.valueOf(used);
                case "homelimit" -> unlimited ? "∞" : String.valueOf(limit);
                case "homes_full" -> used + "/" + (unlimited ? "∞" : String.valueOf(limit));
                case "homes_left" -> unlimited ? "∞" : String.valueOf(Math.max(0, limit - used));
                case "playtime" -> on == null ? "" : Dur.format(TpaManager.playMinutes(on) * 60_000L);
                case "playtime_minutes" -> on == null ? "" : String.valueOf(TpaManager.playMinutes(on));
                case "playtime_hours" -> on == null ? "" : String.valueOf(TpaManager.playMinutes(on) / 60);
                case "favorites" -> String.valueOf(plugin.favorites.list(id).size());
                case "favorites_online" -> String.valueOf(plugin.favorites.list(id).stream()
                        .filter(f -> Bukkit.getPlayer(f) != null).count());
                case "discord" -> {
                    DiscordLink.Link l = plugin.discord.get(id);
                    yield l == null ? "Not linked" : l.discordName();
                }
                case "linked" -> plugin.discord.get(id) != null ? "Yes" : "No";
                case "nightvision" -> plugin.settings.nightVision(id) ? "On" : "Off";
                case "tpa_requests" -> plugin.settings.acceptsTpa(id) ? "On" : "Off";
                case "tpahere_requests" -> plugin.settings.acceptsTpaHere(id) ? "On" : "Off";
                case "combat" -> on != null && plugin.combat.inCombat(on) ? "Yes" : "No";
                case "combat_seconds" -> on == null ? "0" : String.valueOf(plugin.combat.remaining(on));
                default -> null;
            };
        } catch (Throwable t) {
            return "";
        }
    }
}
