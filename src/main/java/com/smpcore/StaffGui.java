package com.smpcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Staff-only punishment menu. Permission is checked on open AND on every click. */
public final class StaffGui {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final SMPCore plugin;

    public StaffGui(SMPCore plugin) {
        this.plugin = plugin;
    }

    private boolean ok(Player p) {
        if (p.hasPermission("smpcore.staff")) return true;
        plugin.msg.send(p, "no-permission");
        return false;
    }

    private Component mm(String s) {
        return MM.deserialize(s);
    }

    private static String esc(String s) {
        return MM.escapeTags(s);
    }

    /** Entry whose action first re-checks that the clicker is still staff. */
    private Menus.Entry guarded(Player p, String label, Component tip, Material icon, Runnable action) {
        return new Menus.Entry(mm(label), tip, icon, () -> {
            if (ok(p)) action.run();
        });
    }

    private Menus.Entry back(Player p, Runnable action) {
        return new Menus.Entry(plugin.msg.text("menus.back-button"), null, Material.ARROW, () -> {
            if (ok(p)) action.run();
        });
    }

    public void openMain(Player p) {
        if (!ok(p)) return;
        HistoryManager h = plugin.history;
        List<Menus.Entry> es = new ArrayList<>();
        es.add(guarded(p, "<red>Punish a player", null, Material.PLAYER_HEAD, () -> openPickPlayer(p)));
        es.add(guarded(p, "<red>Active bans (" + h.activeCount(PunishManager.Type.BAN) + ")", null,
                Material.BARRIER, () -> openList(p, "Active bans", h.active(PunishManager.Type.BAN))));
        es.add(guarded(p, "<gold>Active timeouts (" + h.activeCount(PunishManager.Type.TIMEOUT) + ")", null,
                Material.CLOCK, () -> openList(p, "Active timeouts", h.active(PunishManager.Type.TIMEOUT))));
        es.add(guarded(p, "<yellow>Recent kicks", null, Material.IRON_BOOTS,
                () -> openList(p, "Kicks", h.ofType(PunishManager.Type.KICK))));
        es.add(guarded(p, "<white>All history", null, Material.BOOK, () -> openList(p, "All history", h.all())));
        plugin.menus.open(p, mm("<aqua>DiamondCraft <gray>| <gold>Staff"),
                mm("<gray>Search: <white>/punish id <ID></white> or <white>/punish player <name></white>"), es, null);
    }

    private String icon(HistoryManager.Entry e) {
        return switch (e.type) {
            case BAN -> "<red>";
            case TIMEOUT -> "<gold>";
            case KICK -> "<yellow>";
        };
    }

    private String statusText(HistoryManager.Entry e) {
        String st = plugin.punisher.status(e);
        if (st.equals("Active")) {
            return e.until == 0 ? "permanent" : Dur.format(e.until - System.currentTimeMillis()) + " left";
        }
        return st.toLowerCase();
    }

    public void openList(Player p, String title, List<HistoryManager.Entry> list) {
        if (!ok(p)) return;
        int max = Math.max(1, plugin.getConfig().getInt("staff.list-size", 100));
        List<Menus.Entry> es = new ArrayList<>();
        for (HistoryManager.Entry e : list.subList(0, Math.min(max, list.size()))) {
            Component tip = mm("<gray>Reason: <white>" + esc(e.reason) + "\n<gray>By: <white>" + esc(e.staff));
            Material mat = e.type == PunishManager.Type.BAN ? Material.RED_CONCRETE
                    : e.type == PunishManager.Type.TIMEOUT ? Material.ORANGE_CONCRETE : Material.YELLOW_CONCRETE;
            es.add(guarded(p, icon(e) + e.id + " <white>" + esc(e.name) + " <gray>" + statusText(e), tip, mat,
                    () -> openEntry(p, e)));
        }
        Component body = es.isEmpty() ? mm("<gray>Nothing here.")
                : mm("<gray>Showing " + es.size() + " of " + list.size() + " (newest first)");
        plugin.menus.open(p, mm("<aqua>DiamondCraft <gray>| <gold>" + esc(title)), body, es, back(p, () -> openMain(p)));
    }

    public void openEntry(Player p, HistoryManager.Entry e) {
        if (!ok(p)) return;
        List<Menus.Entry> es = new ArrayList<>();
        String perm = e.type == PunishManager.Type.BAN ? "smpcore.unban" : "smpcore.timeout";
        if (e.active() && p.hasPermission(perm)) {
            String what = e.type == PunishManager.Type.BAN ? "Unban" : "Remove timeout";
            es.add(guarded(p, "<green>" + what, null, Material.LIME_CONCRETE, () -> confirm(p,
                    mm("<gray>" + what + " <white>" + esc(e.name) + " <gray>(" + e.id + ")?"),
                    () -> {
                        plugin.punisher.liftEntry(p, e);
                        openMain(p);
                    })));
        }
        plugin.menus.open(p, mm("<aqua>DiamondCraft <gray>| <gold>" + e.id), plugin.punisher.describe(e), es,
                back(p, () -> openMain(p)));
    }

    // ---------------------------------------------------------------- punish flow
    public void openPickPlayer(Player p) {
        if (!ok(p)) return;
        List<Menus.Entry> es = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.getUniqueId().equals(p.getUniqueId()) || o.hasPermission("smpcore.punish.exempt")) continue;
            es.add(guarded(p, "<yellow>" + esc(o.getName()), null, Material.PLAYER_HEAD,
                    () -> openPickType(p, o.getUniqueId(), o.getName())));
        }
        plugin.menus.open(p, mm("<aqua>DiamondCraft <gray>| <gold>Punish a player"),
                mm(es.isEmpty() ? "<red>No other players are online. Use the commands for offline players."
                        : "<gray>Pick a player."), es, back(p, () -> openMain(p)));
    }

    private void openPickType(Player p, UUID id, String name) {
        if (!ok(p)) return;
        List<Menus.Entry> es = new ArrayList<>();
        if (p.hasPermission("smpcore.ban")) {
            es.add(guarded(p, "<red>Ban", null, Material.BARRIER,
                    () -> openPickDuration(p, id, name, PunishManager.Type.BAN)));
        }
        if (p.hasPermission("smpcore.timeout")) {
            es.add(guarded(p, "<gold>Timeout", null, Material.CLOCK,
                    () -> openPickDuration(p, id, name, PunishManager.Type.TIMEOUT)));
        }
        if (p.hasPermission("smpcore.kick") && Bukkit.getPlayer(id) != null) {
            es.add(guarded(p, "<yellow>Kick", null, Material.IRON_BOOTS,
                    () -> openPickReason(p, id, name, PunishManager.Type.KICK, 0,
                            () -> openPickType(p, id, name))));
        }
        plugin.menus.open(p, mm("<aqua>DiamondCraft <gray>| <gold>" + esc(name)), mm("<gray>Choose an action."),
                es, back(p, () -> openPickPlayer(p)));
    }

    private String durText(long ms) {
        return ms == 0 ? plugin.getConfig().getString("punishments.permanent-text", "Permanent") : Dur.format(ms);
    }

    private void openPickDuration(Player p, UUID id, String name, PunishManager.Type type) {
        if (!ok(p)) return;
        List<String> raw = plugin.getConfig().getStringList(
                type == PunishManager.Type.BAN ? "staff.ban-durations" : "staff.timeout-durations");
        List<Menus.Entry> es = new ArrayList<>();
        for (String r : raw) {
            long d = Dur.parse(r);
            if (d < 0 || (type == PunishManager.Type.TIMEOUT && d == 0)) continue;
            es.add(guarded(p, "<white>" + durText(d), null, Material.CLOCK,
                    () -> openPickReason(p, id, name, type, d, () -> openPickDuration(p, id, name, type))));
        }
        plugin.menus.open(p, mm("<aqua>DiamondCraft <gray>| <gold>" + type + " " + esc(name)),
                mm("<gray>How long?"), es, back(p, () -> openPickType(p, id, name)));
    }

    private void openPickReason(Player p, UUID id, String name, PunishManager.Type type, long dur, Runnable backTo) {
        if (!ok(p)) return;
        List<String> reasons = plugin.getConfig().getStringList("staff.reasons");
        if (reasons.isEmpty()) reasons = List.of("Rule violation");
        List<Menus.Entry> es = new ArrayList<>();
        for (String reason : reasons) {
            es.add(guarded(p, "<white>" + esc(reason), null, Material.PAPER, () -> confirm(p,
                    mm("<gray>" + type + " <white>" + esc(name)
                            + (type == PunishManager.Type.KICK ? "" : " <gray>for <white>" + durText(dur))
                            + "\n<gray>Reason: <white>" + esc(reason)),
                    () -> {
                        plugin.punisher.punish(p, type, id, name, dur, reason);
                        openMain(p);
                    })));
        }
        plugin.menus.open(p, mm("<aqua>DiamondCraft <gray>| <gold>Reason"),
                mm("<gray>A reason is required. For a custom reason use the command."), es, back(p, backTo));
    }

    private void confirm(Player p, Component summary, Runnable action) {
        if (!ok(p)) return;
        List<Menus.Entry> es = new ArrayList<>();
        es.add(guarded(p, "<green>Confirm", null, Material.LIME_CONCRETE, action));
        es.add(guarded(p, "<red>Cancel", null, Material.RED_CONCRETE, () -> openMain(p)));
        plugin.menus.open(p, mm("<aqua>DiamondCraft <gray>| <gold>Confirm"), summary, es, null);
    }
}
