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
        Player found = null;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (strip(p.getName()).equalsIgnoreCase(strip(name))) {
                if (found != null) return null; // two players match: do not guess
                found = p;
            }
        }
        return found;
    }

    private boolean econOn(CommandSender s) {
        if (!plugin.economy.enabled()) {
            plugin.msg.send(s, "economy-disabled");
            return false;
        }
        return true;
    }

    /** 1500, 1.5k, 2m, 1b. Returns -1 when invalid. */
    private double parseAmount(String raw) {
        try {
            String t = raw.replace(",", "").toLowerCase(Locale.ROOT);
            double mult = 1;
            if (t.endsWith("k")) mult = 1e3;
            else if (t.endsWith("m")) mult = 1e6;
            else if (t.endsWith("b")) mult = 1e9;
            if (mult != 1) t = t.substring(0, t.length() - 1);
            double v = Double.parseDouble(t) * mult;
            return Double.isNaN(v) || Double.isInfinite(v) || v < 0 || v > 1e12 ? -1 : v;
        } catch (NumberFormatException e) {
            return -1;
        }
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
                Map<String, HomeManager.Home> h = plugin.homes.all(p.getUniqueId());
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
                Map<String, HomeManager.Home> h = plugin.homes.all(p.getUniqueId());
                if (a.length == 0) {
                    if (h.isEmpty()) {
                        msg.send(p, "home-none");
                    } else if (plugin.getConfig().getBoolean("homes.open-menu-on-home", true)) {
                        plugin.gui.openHomes(p);
                    } else {
                        String first = h.containsKey("home") ? "home" : h.keySet().iterator().next();
                        plugin.gui.teleportHome(p, first);
                    }
                    return true;
                }
                String name = a[0].toLowerCase(Locale.ROOT);
                if (!h.containsKey(name)) {
                    msg.send(p, "home-not-found", "home", name);
                    return true;
                }
                plugin.gui.teleportHome(p, name);
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
                    Net.OnlineInfo ri = plugin.net == null ? null : plugin.net.findRemote(a[0]);
                    if (ri != null) {
                        plugin.tpa.requestRemote(p, ri, here);
                        return true;
                    }
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
                if (a.length < 2) {
                    msg.send(s, "usage-ban");
                    return true;
                }
                long dur = 0;
                int ri = 1;
                long d = Dur.parse(a[1]);
                if (d >= 0) {
                    dur = d;
                    ri = 2;
                }
                final long fdur = dur;
                final String why = reason(a, ri, "");
                plugin.players.resolve(a[0], t -> {
                    if (t == null) {
                        msg.send(s, "player-unknown");
                        return;
                    }
                    plugin.punisher.punish(s, PunishManager.Type.BAN, t.id(), t.name(), fdur, why);
                });
            }
            case "unban" -> {
                if (a.length < 1) {
                    msg.send(s, "usage-unban");
                    return true;
                }
                plugin.punisher.lift(s, PunishManager.Type.BAN, a[0]);
            }
            case "kick" -> {
                if (a.length < 2) {
                    msg.send(s, "usage-kick");
                    return true;
                }
                Player t = online(a[0]);
                if (t == null) {
                    msg.send(s, "player-not-found");
                    return true;
                }
                plugin.punisher.punish(s, PunishManager.Type.KICK, t.getUniqueId(), t.getName(), 0, reason(a, 1, ""));
            }
            case "timeout" -> {
                if (a.length < 3) {
                    msg.send(s, "usage-timeout");
                    return true;
                }
                final long dur = Dur.parse(a[1]);
                if (dur <= 0) {
                    msg.send(s, "bad-duration");
                    return true;
                }
                final String why = reason(a, 2, "");
                plugin.players.resolve(a[0], t -> {
                    if (t == null) {
                        msg.send(s, "player-unknown");
                        return;
                    }
                    plugin.punisher.punish(s, PunishManager.Type.TIMEOUT, t.id(), t.name(), dur, why);
                });
            }
            case "untimeout" -> {
                if (a.length < 1) {
                    msg.send(s, "usage-untimeout");
                    return true;
                }
                plugin.punisher.lift(s, PunishManager.Type.TIMEOUT, a[0]);
            }
            case "link" -> {
                Player p = player(s);
                if (p != null) plugin.discord.link(p);
            }
            case "unlink" -> {
                Player p = player(s);
                if (p != null) plugin.discord.unlink(p);
            }
            case "punish" -> {
                if (!s.hasPermission("smpcore.staff")) {
                    msg.send(s, "no-permission");
                    return true;
                }
                if (a.length >= 2 && a[0].equalsIgnoreCase("id")) {
                    HistoryManager.Entry e = plugin.history.get(a[1]);
                    if (e == null) {
                        msg.send(s, "id-not-found");
                    } else if (s instanceof Player pl) {
                        plugin.staffGui.openEntry(pl, e);
                    } else {
                        s.sendMessage(plugin.punisher.describe(e));
                    }
                    return true;
                }
                if (a.length >= 2 && a[0].equalsIgnoreCase("player")) {
                    plugin.players.resolve(a[1], t -> {
                        if (t == null) {
                            msg.send(s, "player-unknown");
                            return;
                        }
                        List<HistoryManager.Entry> list = plugin.history.byPlayer(t.id(), t.name());
                        if (s instanceof Player pl) plugin.staffGui.openList(pl, t.name(), list);
                        else list.forEach(e -> s.sendMessage(plugin.punisher.describe(e)));
                    });
                    return true;
                }
                Player sp = player(s);
                if (sp != null) plugin.staffGui.openMain(sp);
            }
            case "smpcore" -> {
                if (a.length > 0 && a[0].equalsIgnoreCase("reload")) {
                    plugin.reload();
                    msg.send(s, "reloaded");
                }
            }
            case "balance" -> {
                if (!econOn(s)) return true;
                if (a.length == 0) {
                    Player p = player(s);
                    if (p == null) return true;
                    msg.send(p, "balance-self", "balance", plugin.economy.format(plugin.economy.get(p.getUniqueId())));
                } else {
                    plugin.players.resolveKnown(a[0], t -> {
                        if (t == null) {
                            msg.send(s, "player-unknown");
                            return;
                        }
                        msg.send(s, "balance-other", "player", t.name(),
                                "balance", plugin.economy.format(plugin.economy.get(t.id())));
                    });
                }
            }
            case "pay" -> {
                if (!econOn(s)) return true;
                Player p = player(s);
                if (p == null) return true;
                if (a.length == 0) {
                    plugin.gui.openPay(p);
                    return true;
                }
                if (a.length < 2) {
                    msg.send(p, "pay-usage");
                    return true;
                }
                final double amt = parseAmount(a[1]);
                if (amt <= 0) {
                    msg.send(p, "pay-invalid");
                    return true;
                }
                plugin.players.resolveKnown(a[0], t -> {
                    if (t == null) {
                        msg.send(p, "player-unknown");
                        return;
                    }
                    plugin.economy.pay(p, t.id(), t.name(), amt);
                });
            }
            case "baltop" -> {
                if (!econOn(s)) return true;
                msg.send(s, "baltop-header");
                int rank = 1;
                for (Map.Entry<java.util.UUID, Double> e : plugin.economy.top(10)) {
                    s.sendMessage(msg.get("baltop-line", "rank", String.valueOf(rank++),
                            "player", plugin.players.nameFor(e.getKey()),
                            "balance", plugin.economy.format(e.getValue())));
                }
            }
            case "eco" -> {
                if (!econOn(s)) return true;
                if (a.length < 3) {
                    msg.send(s, "eco-usage");
                    return true;
                }
                final String sub = a[0].toLowerCase(Locale.ROOT);
                final double amt = parseAmount(a[2]);
                if (!List.of("give", "take", "set").contains(sub) || amt < 0 || (!sub.equals("set") && amt == 0)) {
                    msg.send(s, "eco-usage");
                    return true;
                }
                plugin.players.resolve(a[1], t -> {
                    if (t == null) {
                        msg.send(s, "player-unknown");
                        return;
                    }
                    EconomyManager e = plugin.economy;
                    switch (sub) {
                        case "give" -> e.deposit(t.id(), amt);
                        case "take" -> e.set(t.id(), e.get(t.id()) - amt);
                        default -> e.set(t.id(), amt);
                    }
                    msg.send(s, "eco-done", "player", t.name(), "balance", e.format(e.get(t.id())));
                    e.audit(s.getName() + " /eco " + sub + " " + t.name() + " " + amt + " -> balance " + e.get(t.id()));
                });
            }
            case "settings" -> {
                Player p = player(s);
                if (p != null) plugin.gui.openSettings(p);
            }
            case "menu" -> {
                Player p = player(s);
                if (p != null) plugin.gui.openMain(p);
            }
            case "status" -> {
                Player p = player(s);
                if (p != null) plugin.gui.openStatus(p);
            }
            case "tpmenu" -> {
                Player p = player(s);
                if (p != null) plugin.gui.openTeleportMenu(p);
            }
            case "tpfav" -> {
                Player p = player(s);
                if (p == null) return true;
                if (a.length == 0) {
                    plugin.gui.openFavorites(p);
                    return true;
                }
                final String sub = a[0].toLowerCase(Locale.ROOT);
                if (a.length < 2 || !(sub.equals("add") || sub.equals("remove"))) {
                    msg.send(p, "fav-usage");
                    return true;
                }
                plugin.players.resolveKnown(a[1], t -> {
                    if (t == null) {
                        msg.send(p, "player-unknown");
                        return;
                    }
                    if (sub.equals("add")) plugin.gui.addFavorite(p, t.id(), t.name());
                    else if (plugin.favorites.remove(p.getUniqueId(), t.id()))
                        msg.send(p, "fav-removed", "player", t.name());
                    else msg.send(p, "fav-not", "player", t.name());
                });
            }
            case "rtp" -> {
                Player p = player(s);
                if (p == null) return true;
                if (!plugin.rtp.rtpOpen()) {
                    msg.send(p, "rtp-closed");
                    return true;
                }
                if (a.length == 0) plugin.gui.openRtp(p);
                else plugin.rtp.start(p, a[0].toLowerCase(Locale.ROOT));
            }
            case "rtpq" -> {
                Player p = player(s);
                if (p == null) return true;
                if (a.length == 0) {
                    plugin.gui.openRtpQueue(p);
                } else if (a[0].equalsIgnoreCase("join")) {
                    plugin.rtp.joinQueue(p);
                } else if (a[0].equalsIgnoreCase("leave")) {
                    plugin.rtp.leaveQueue(p);
                } else {
                    msg.send(p, "rtpq-usage");
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
                case "pay", "balance" -> Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
                case "eco" -> out.addAll(List.of("give", "take", "set"));
                case "tpfav" -> out.addAll(List.of("add", "remove"));
                case "punish" -> out.addAll(List.of("id", "player"));
                case "rtp" -> out.addAll(plugin.rtp.openWorldKeys());
                case "rtpq" -> out.addAll(List.of("join", "leave"));
                default -> { }
            }
        } else if (a.length == 2 && (cmd.equals("eco") || cmd.equals("tpfav"))) {
            Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
        } else if (a.length == 2 && (cmd.equals("ban") || cmd.equals("timeout"))) {
            out.addAll(List.of("30m", "1h", "6h", "1d", "7d", "30d", "perm"));
        }
        if (plugin.net != null && a.length == 1 && (cmd.equals("tpa") || cmd.equals("tpahere"))) {
            plugin.net.remotePlayers().forEach(o -> out.add(o.name()));
        }
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(cur));
        return out;
    }
}
