package com.smpcore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Cmds implements TabExecutor {
    private final SMPCore plugin;

    public Cmds(SMPCore plugin) {
        this.plugin = plugin;
    }

    private Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        plugin.msg.send(s, "player-only");
        return null;
    }

    private String reason(String[] a, int from, String def) {
        return a.length > from ? String.join(" ", Arrays.copyOfRange(a, from, a.length)) : def;
    }

    private static String strip(String n) {
        int i = 0;
        while (i < n.length() && !Character.isLetterOrDigit(n.charAt(i)) && n.charAt(i) != '_') i++;
        return n.substring(i);
    }

    /** Online player by exact name, or by name ignoring the Bedrock prefix. */
    private Player online(String name) {
        Player exact = Bukkit.getPlayerExact(name);
        if (exact != null) return exact;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (strip(p.getName()).equalsIgnoreCase(strip(name))) return p;
        }
        return null;
    }

    private String perm() {
        return plugin.getConfig().getString("punishments.permanent-text", "Permanent");
    }

    private void broadcast(String key, PunishManager.Rec r) {
        if (!plugin.getConfig().getBoolean("punishments.broadcast", true)) return;
        String perm = plugin.getConfig().getString("punishments.permanent-text", "Permanent");
        String dur = r.until() == 0 ? perm : Dur.format(r.until() - System.currentTimeMillis());
        Bukkit.broadcast(plugin.msg.prefixed(key, "player", r.name(), "staff", r.staff(),
                "reason", r.reason(), "duration", dur));
    }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        Msg msg = plugin.msg;
        switch (c.getName().toLowerCase(Locale.ROOT)) {
            case "sethome" -> {
                Player p = player(s);
                if (p == null) return true;
                String name = a.length > 0 ? a[0].toLowerCase(Locale.ROOT) : "home";
                int max = plugin.getConfig().getInt("homes.max-name-length", 16);
                if (!name.matches("[a-z0-9_-]+") || name.length() > max) {
                    msg.send(p, "home-invalid-name", "max", String.valueOf(max));
                    return true;
                }
                Map<String, Location> h = plugin.homes.all(p.getUniqueId());
                int limit = plugin.homes.limit(p);
                if (!h.containsKey(name) && h.size() >= limit) {
                    msg.send(p, "home-limit", "limit", plugin.homes.limitText(p));
                    return true;
                }
                plugin.homes.set(p.getUniqueId(), name, p.getLocation());
                msg.send(p, "home-set", "home", name);
            }
            case "home" -> {
                Player p = player(s);
                if (p == null) return true;
                Map<String, Location> h = plugin.homes.all(p.getUniqueId());
                if (a.length == 0) {
                    if (h.isEmpty()) {
                        msg.send(p, "home-none");
                    } else if (plugin.getConfig().getBoolean("homes.open-menu-on-home", true)) {
                        plugin.gui.openHomes(p);
                    } else {
                        String first = h.containsKey("home") ? "home" : h.keySet().iterator().next();
                        plugin.tp.go(p, () -> plugin.homes.get(p.getUniqueId(), first), first);
                    }
                    return true;
                }
                String name = a[0].toLowerCase(Locale.ROOT);
                if (!h.containsKey(name)) {
                    msg.send(p, "home-not-found", "home", name);
                    return true;
                }
                plugin.tp.go(p, () -> plugin.homes.get(p.getUniqueId(), name), name);
            }
            case "homes" -> {
                Player p = player(s);
                if (p != null) plugin.gui.openHomes(p);
            }
            case "delhome" -> {
                Player p = player(s);
                if (p == null) return true;
                if (a.length < 1) {
                    plugin.gui.openHomes(p);
                    return true;
                }
                String name = a[0].toLowerCase(Locale.ROOT);
                if (plugin.homes.delete(p.getUniqueId(), name)) msg.send(p, "home-deleted", "home", name);
                else msg.send(p, "home-not-found", "home", name);
            }
            case "tpa", "tpahere" -> {
                Player p = player(s);
                if (p == null) return true;
                boolean here = c.getName().equalsIgnoreCase("tpahere");
                if (a.length < 1) {
                    plugin.gui.openTpa(p, here);
                    return true;
                }
                Player t = online(a[0]);
                if (t == null) {
                    msg.send(p, "player-not-found");
                    return true;
                }
                plugin.tpa.request(p, t, here);
            }
            case "tpaccept" -> {
                Player p = player(s);
                if (p != null) plugin.tpa.accept(p, a.length > 0 ? a[0] : null);
            }
            case "tpdeny" -> {
                Player p = player(s);
                if (p != null) plugin.tpa.deny(p, a.length > 0 ? a[0] : null);
            }
            case "tpacancel" -> {
                Player p = player(s);
                if (p != null) plugin.tpa.cancel(p);
            }
            case "ban" -> {
                if (a.length < 1) {
                    msg.send(s, "usage-ban");
                    return true;
                }
                long dur = 0;
                int ri = 1;
                if (a.length > 1) {
                    long d = Dur.parse(a[1]);
                    if (d >= 0) {
                        dur = d;
                        ri = 2;
                    }
                }
                final long fdur = dur;
                final String why = reason(a, ri, plugin.getConfig().getString("punishments.default-ban-reason", ""));
                plugin.players.resolve(a[0], t -> {
                    if (t == null) {
                        msg.send(s, "player-unknown");
                        return;
                    }
                    long until = fdur == 0 ? 0 : System.currentTimeMillis() + fdur;
                    PunishManager.Rec r = new PunishManager.Rec(PunishManager.Type.BAN, t.id(), t.name(),
                            until, why, s.getName());
                    plugin.punish.add(r);
                    Player on = Bukkit.getPlayer(t.id());
                    if (on != null) on.kick(msg.screen("ban", r));
                    msg.send(s, "done-ban", "player", r.name(), "duration", until == 0 ? perm() : Dur.format(fdur));
                    broadcast("broadcast-ban", r);
                });
            }
            case "unban" -> {
                if (a.length < 1) {
                    msg.send(s, "usage-unban");
                    return true;
                }
                if (plugin.punish.removeByName(a[0], PunishManager.Type.BAN)) {
                    msg.send(s, "done-unban", "player", a[0]);
                } else {
                    plugin.players.resolve(a[0], t -> {
                        if (t != null && plugin.punish.remove(t.id(), PunishManager.Type.BAN))
                            msg.send(s, "done-unban", "player", t.name());
                        else msg.send(s, "not-banned");
                    });
                }
            }
            case "kick" -> {
                if (a.length < 1) {
                    msg.send(s, "usage-kick");
                    return true;
                }
                Player t = online(a[0]);
                if (t == null) {
                    msg.send(s, "player-not-found");
                    return true;
                }
                PunishManager.Rec r = new PunishManager.Rec(null, t.getUniqueId(), t.getName(), 0,
                        reason(a, 1, plugin.getConfig().getString("punishments.default-kick-reason", "")),
                        s.getName());
                t.kick(msg.screen("kick", r));
                msg.send(s, "done-kick", "player", t.getName());
                broadcast("broadcast-kick", r);
            }
            case "timeout" -> {
                if (a.length < 2) {
                    msg.send(s, "usage-timeout");
                    return true;
                }
                long dur = Dur.parse(a[1]);
                if (dur <= 0) {
                    msg.send(s, "bad-duration");
                    return true;
                }
                final String why = reason(a, 2, plugin.getConfig().getString("punishments.default-timeout-reason", ""));
                plugin.players.resolve(a[0], t -> {
                    if (t == null) {
                        msg.send(s, "player-unknown");
                        return;
                    }
                    PunishManager.Rec r = new PunishManager.Rec(PunishManager.Type.TIMEOUT, t.id(), t.name(),
                            System.currentTimeMillis() + dur, why, s.getName());
                    plugin.punish.add(r);
                    Player on = Bukkit.getPlayer(t.id());
                    if (on != null) on.kick(msg.screen("timeout", r));
                    msg.send(s, "done-timeout", "player", r.name(), "duration", Dur.format(dur));
                    broadcast("broadcast-timeout", r);
                });
            }
            case "untimeout" -> {
                if (a.length < 1) {
                    msg.send(s, "usage-untimeout");
                    return true;
                }
                if (plugin.punish.removeByName(a[0], PunishManager.Type.TIMEOUT)) {
                    msg.send(s, "done-untimeout", "player", a[0]);
                } else {
                    plugin.players.resolve(a[0], t -> {
                        if (t != null && plugin.punish.remove(t.id(), PunishManager.Type.TIMEOUT))
                            msg.send(s, "done-untimeout", "player", t.name());
                        else msg.send(s, "not-timed-out");
                    });
                }
            }
            case "smpcore" -> {
                if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
                    plugin.reload();
                    msg.send(s, "reloaded");
                }
            }
            default -> { }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        String cmd = c.getName().toLowerCase(Locale.ROOT);
        String cur = a[a.length - 1].toLowerCase(Locale.ROOT);
        if (a.length == 1) {
            switch (cmd) {
                case "home", "delhome" -> {
                    if (s instanceof Player p) out.addAll(plugin.homes.all(p.getUniqueId()).keySet());
                }
                case "tpa", "tpahere", "tpaccept", "tpdeny", "ban", "kick", "timeout" ->
                        Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                case "unban" -> out.addAll(plugin.punish.names(PunishManager.Type.BAN));
                case "untimeout" -> out.addAll(plugin.punish.names(PunishManager.Type.TIMEOUT));
                case "smpcore" -> out.add("reload");
                default -> { }
            }
        } else if (a.length == 2 && (cmd.equals("ban") || cmd.equals("timeout"))) {
            out.addAll(List.of("30m", "1h", "6h", "1d", "7d", "30d", "perm"));
        }
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(cur));
        return out;
    }
}
