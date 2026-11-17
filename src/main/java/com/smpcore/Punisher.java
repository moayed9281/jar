package com.smpcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

/** The ONE place where bans, timeouts and kicks happen (commands and menus both use it). */
public final class Punisher {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final SMPCore plugin;

    public Punisher(SMPCore plugin) {
        this.plugin = plugin;
    }

    private static String esc(String s) {
        return MM.escapeTags(s);
    }

    private String clean(String raw) {
        String s = raw == null ? "" : raw.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        int max = Math.max(10, plugin.getConfig().getInt("staff.max-reason-length", 200));
        return s.length() > max ? s.substring(0, max) : s;
    }

    private boolean isProtected(UUID id, String name) {
        for (String s : plugin.getConfig().getStringList("staff.protected")) {
            if (s.equalsIgnoreCase(name) || s.equalsIgnoreCase(id.toString())) return true;
        }
        return false;
    }

    private String permanent() {
        return plugin.getConfig().getString("punishments.permanent-text", "Permanent");
    }

    public void punish(CommandSender by, PunishManager.Type type, UUID id, String name, long durMs, String rawReason) {
        Msg m = plugin.msg;
        String perm = switch (type) {
            case BAN -> "smpcore.ban";
            case TIMEOUT -> "smpcore.timeout";
            case KICK -> "smpcore.kick";
        };
        if (!by.hasPermission(perm)) {
            m.send(by, "no-permission");
            return;
        }
        String reason = clean(rawReason);
        int min = Math.max(1, plugin.getConfig().getInt("staff.min-reason-length", 3));
        if (reason.length() < min) {
            m.send(by, "reason-required", "min", String.valueOf(min));
            return;
        }
        if (by instanceof Player self && self.getUniqueId().equals(id)) {
            m.send(by, "punish-self");
            return;
        }
        Player on = Bukkit.getPlayer(id);
        if (by instanceof Player) {
            boolean exempt = on != null && on.hasPermission("smpcore.punish.exempt");
            boolean targetOp = Bukkit.getOfflinePlayer(id).isOp();
            if (exempt || isProtected(id, name) || (targetOp && !by.isOp())) {
                m.send(by, "punish-exempt", "player", name);
                plugin.getLogger().warning("[STAFF] " + by.getName() + " tried to " + type + " protected player " + name);
                return;
            }
        }
        if (type == PunishManager.Type.KICK && on == null) {
            m.send(by, "player-not-found");
            return;
        }
        if (type == PunishManager.Type.TIMEOUT && durMs <= 0) {
            m.send(by, "bad-duration");
            return;
        }
        long until = (type == PunishManager.Type.KICK || durMs <= 0) ? 0 : System.currentTimeMillis() + durMs;

        if (type != PunishManager.Type.KICK) {
            PunishManager.Rec existing = plugin.punish.get(id, type);
            boolean shorter = existing != null && until != 0 && (existing.until() == 0 || until < existing.until());
            if (shorter && !by.hasPermission("smpcore.punish.override")) {
                m.send(by, "punish-downgrade", "id", existing.punishId() == null || existing.punishId().isEmpty()
                        ? "-" : existing.punishId());
                return;
            }
            plugin.history.liftActiveById(id, type, "(replaced)");
        }
        HistoryManager.Entry e = plugin.history.create(type, id, name, by.getName(), reason, until);
        PunishManager.Rec rec = new PunishManager.Rec(type, id, name, until, reason, by.getName(), e.id);
        if (type != PunishManager.Type.KICK) plugin.punish.add(rec);

        String key = type.name().toLowerCase();
        if (on != null) on.kick(m.screen(key, rec));
        String dur = until == 0 ? permanent() : Dur.format(durMs);
        m.send(by, "done-" + key, "player", name, "duration", dur);
        m.send(by, "punish-id", "id", e.id);
        if (plugin.getConfig().getBoolean("punishments.broadcast", true)) {
            Bukkit.broadcast(m.prefixed("broadcast-" + key, "player", name, "staff", by.getName(),
                    "reason", reason, "duration", dur));
        }
        plugin.getLogger().info("[STAFF] " + by.getName() + " " + key + " " + name + " (" + e.id + ") for "
                + dur + ": " + reason);
    }

    /** Unban / remove timeout by typed name. */
    public void lift(CommandSender by, PunishManager.Type type, String input) {
        Msg m = plugin.msg;
        String perm = type == PunishManager.Type.BAN ? "smpcore.unban" : "smpcore.timeout";
        if (!by.hasPermission(perm)) {
            m.send(by, "no-permission");
            return;
        }
        String doneKey = type == PunishManager.Type.BAN ? "done-unban" : "done-untimeout";
        String missKey = type == PunishManager.Type.BAN ? "not-banned" : "not-timed-out";
        PunishManager.Rec removed = plugin.punish.removeByName(input, type);
        if (removed != null) {
            plugin.history.liftActiveById(removed.id(), type, by.getName());
            m.send(by, doneKey, "player", removed.name());
            plugin.getLogger().info("[STAFF] " + by.getName() + " lifted " + type + " of " + input);
            return;
        }
        plugin.players.resolve(input, t -> {
            if (t != null && plugin.punish.remove(t.id(), type)) {
                plugin.history.liftActiveById(t.id(), type, by.getName());
                m.send(by, doneKey, "player", t.name());
                plugin.getLogger().info("[STAFF] " + by.getName() + " lifted " + type + " of " + t.name());
            } else {
                m.send(by, missKey);
            }
        });
    }

    /** Lift from the menu (one specific entry). */
    public void liftEntry(CommandSender by, HistoryManager.Entry e) {
        Msg m = plugin.msg;
        String perm = e.type == PunishManager.Type.BAN ? "smpcore.unban" : "smpcore.timeout";
        if (!by.hasPermission(perm)) {
            m.send(by, "no-permission");
            return;
        }
        if (!e.active()) {
            m.send(by, "not-active");
            return;
        }
        plugin.punish.remove(e.uuid, e.type);
        plugin.history.lift(e, by.getName());
        m.send(by, e.type == PunishManager.Type.BAN ? "done-unban" : "done-untimeout", "player", e.name);
        plugin.getLogger().info("[STAFF] " + by.getName() + " lifted " + e.id + " (" + e.name + ")");
    }

    public String status(HistoryManager.Entry e) {
        if (e.type == PunishManager.Type.KICK) return "Kick";
        if (e.liftedAt > 0) return "Lifted";
        if (e.until != 0 && e.until <= System.currentTimeMillis()) return "Expired";
        return "Active";
    }

    /** All details of one punishment, ready to show. Values are escaped. */
    public Component describe(HistoryManager.Entry e) {
        var cfg = plugin.getConfig();
        SimpleDateFormat f = new SimpleDateFormat(cfg.getString("punishments.date-format", "yyyy-MM-dd HH:mm z"));
        String expires = e.type == PunishManager.Type.KICK ? "-" : e.until == 0 ? permanent() : f.format(new Date(e.until));
        StringBuilder sb = new StringBuilder();
        sb.append("<gold>").append(e.type).append(" <white>").append(e.id).append("\n");
        sb.append("<gray>Player: <white>").append(esc(e.name)).append("\n");
        sb.append("<gray>By: <white>").append(esc(e.staff)).append("\n");
        sb.append("<gray>Reason: <white>").append(esc(e.reason)).append("\n");
        sb.append("<gray>Date: <white>").append(f.format(new Date(e.created))).append("\n");
        if (e.type != PunishManager.Type.KICK) sb.append("<gray>Expires: <white>").append(expires).append("\n");
        sb.append("<gray>Status: <white>").append(status(e));
        if (e.liftedAt > 0) {
            sb.append("\n<gray>Lifted by: <white>").append(esc(e.liftedBy)).append(" <gray>on <white>")
                    .append(f.format(new Date(e.liftedAt)));
        }
        return MM.deserialize(sb.toString());
    }
}
