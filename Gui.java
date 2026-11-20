package com.smpcore;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class Gui {
    private final SMPCore plugin;

    public Gui(SMPCore plugin) {
        this.plugin = plugin;
    }

    private Menus.Entry back(Runnable action) {
        return new Menus.Entry(plugin.msg.text("menus.back-button"), null, Material.ARROW, action);
    }

    public void openHomes(Player p) {
        Msg m = plugin.msg;
        UUID id = p.getUniqueId();
        Map<String, HomeManager.Home> homes = plugin.homes.all(id);
        int limit = plugin.homes.limit(p);
        List<Menus.Entry> entries = new ArrayList<>();
        for (Map.Entry<String, HomeManager.Home> en : homes.entrySet()) {
            String name = en.getKey();
            HomeManager.Home h = en.getValue();
            String where = h.world() + " " + (int) h.x() + ", " + (int) h.y() + ", " + (int) h.z();
            if (!h.server().equalsIgnoreCase(plugin.serverName())) where += " (" + h.server() + ")";
            Component tip = m.mm("<gray>" + net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().escapeTags(where));
            entries.add(new Menus.Entry(m.text("menus.home-button", "home", name), tip, Material.RED_BED,
                    () -> openHome(p, name)));
        }
        if (homes.size() < limit) {
            entries.add(new Menus.Entry(m.text("menus.set-home-button"), null, Material.LIME_DYE, () -> {
                String n = "home";
                int i = 2;
                while (homes.containsKey(n)) n = "home" + i++;
                plugin.homes.set(id, n, p.getLocation());
                m.send(p, "home-set", "home", n);
                openHomes(p);
            }));
        }
        Component title = m.text("menus.home-title", "used", String.valueOf(homes.size()),
                "limit", plugin.homes.limitText(p));
        plugin.menus.open(p, title, m.text("menus.home-info"), entries, null);
    }

    public void openHome(Player p, String name) {
        Msg m = plugin.msg;
        UUID id = p.getUniqueId();
        List<Menus.Entry> entries = new ArrayList<>();
        entries.add(new Menus.Entry(m.text("menus.teleport-button"), null, Material.ENDER_PEARL,
                () -> teleportHome(p, name)));
        entries.add(new Menus.Entry(m.text("menus.delete-button"), null, Material.BARRIER, () -> {
            plugin.homes.delete(id, name);
            m.send(p, "home-deleted", "home", name);
            openHomes(p);
        }));
        plugin.menus.open(p, m.text("menus.home-actions-title", "home", name), null, entries,
                back(() -> openHomes(p)));
    }

    /** Teleport to a home, also when it is on another server of the network. */
    public void teleportHome(Player p, String name) {
        UUID id = p.getUniqueId();
        HomeManager.Home h = plugin.homes.get(id, name);
        if (h == null) {
            plugin.msg.send(p, "home-not-found", "home", name.toLowerCase());
            return;
        }
        if (h.server().equalsIgnoreCase(plugin.serverName())) {
            plugin.tp.go(p, () -> {
                HomeManager.Home x = plugin.homes.get(id, name);
                return x == null ? null : x.toLocation();
            }, name);
        } else if (plugin.net != null) {
            plugin.tp.countdown(p, () -> plugin.net.sendToHome(p, h));
        } else {
            plugin.msg.send(p, "home-other-server", "server", h.server());
        }
    }

    public void openTpa(Player p, boolean here) {
        Msg m = plugin.msg;
        UUID me = p.getUniqueId();
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.removeIf(o -> o.getUniqueId().equals(me));
        online.sort(Comparator.comparing((Player o) -> !plugin.favorites.has(me, o.getUniqueId()))
                .thenComparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        List<Menus.Entry> entries = new ArrayList<>();
        for (Player other : online) {
            boolean fav = plugin.favorites.has(me, other.getUniqueId());
            Component label = m.mm((fav ? "<gold>\u2605 <yellow>" : "<yellow>") + other.getName());
            entries.add(new Menus.Entry(label, null, Material.PLAYER_HEAD,
                    () -> plugin.tpa.request(p, other, here)));
        }
        if (plugin.net != null) {
            List<Net.OnlineInfo> remote = new ArrayList<>(plugin.net.remotePlayers());
            remote.sort(Comparator.comparing((Net.OnlineInfo o) -> !plugin.favorites.has(me, o.id()))
                    .thenComparing(Net.OnlineInfo::name, String.CASE_INSENSITIVE_ORDER));
            for (Net.OnlineInfo o : remote) {
                boolean fav = plugin.favorites.has(me, o.id());
                Component label = m.mm((fav ? "<gold>\u2605 <yellow>" : "<yellow>") + o.name() + " <gray>(" + o.server() + ")");
                entries.add(new Menus.Entry(label, null, Material.PLAYER_HEAD,
                        () -> plugin.tpa.requestRemote(p, o, here)));
            }
        }
        Component msg = entries.isEmpty() ? m.text("menus.tpa-no-players") : m.text("menus.tpa-info");
        plugin.menus.open(p, m.text(here ? "menus.tpahere-title" : "menus.tpa-title"), msg, entries,
                back(() -> openTeleportMenu(p)));
    }

    /** Teleport hub: TPA, TPA Here and Favorites in one menu. */
    public void openTeleportMenu(Player p) {
        Msg m = plugin.msg;
        List<Menus.Entry> es = new ArrayList<>();
        if (plugin.getConfig().getBoolean("tpa.tpa-enabled", true)) {
            es.add(new Menus.Entry(m.text("menus.tpa-menu-button"), null, Material.ENDER_PEARL,
                    () -> openTpa(p, false)));
        }
        if (plugin.getConfig().getBoolean("tpa.tpahere-enabled", true)) {
            es.add(new Menus.Entry(m.text("menus.tpahere-menu-button"), null, Material.ENDER_EYE,
                    () -> openTpa(p, true)));
        }
        es.add(new Menus.Entry(m.text("menus.favorites-button"), null, Material.NETHER_STAR,
                () -> openFavorites(p)));
        plugin.menus.open(p, m.text("menus.teleport-title"), m.text("menus.teleport-info"), es, null);
    }

    public void openFavorites(Player p) {
        Msg m = plugin.msg;
        List<UUID> favs = plugin.favorites.list(p.getUniqueId());
        favs.sort(Comparator.comparing((UUID id) -> Bukkit.getPlayer(id) == null));
        List<Menus.Entry> es = new ArrayList<>();
        for (UUID id : favs) {
            String name = plugin.players.nameFor(id);
            boolean on = onlineAnywhere(id);
            Component label = on ? m.mm("<gold>★ <green>" + name) : m.mm("<gray>★ " + name + " (offline)");
            es.add(new Menus.Entry(label, null, Material.PLAYER_HEAD, () -> openFavoriteActions(p, id, name)));
        }
        es.add(new Menus.Entry(m.text("menus.fav-add-button"), null, Material.LIME_DYE, () -> openFavAdd(p)));
        plugin.menus.open(p, m.text("menus.fav-title"),
                m.text(favs.isEmpty() ? "menus.fav-empty" : "menus.fav-info"), es, back(() -> openTeleportMenu(p)));
    }

    private boolean onlineAnywhere(UUID id) {
        return Bukkit.getPlayer(id) != null || (plugin.net != null && plugin.net.remoteById(id) != null);
    }

    private void quick(Player p, UUID id, boolean here) {
        Player t = Bukkit.getPlayer(id);
        if (t != null) {
            plugin.tpa.request(p, t, here);
            return;
        }
        Net.OnlineInfo ri = plugin.net == null ? null : plugin.net.remoteById(id);
        if (ri != null) {
            plugin.tpa.requestRemote(p, ri, here);
            return;
        }
        plugin.msg.send(p, "tpa-offline");
    }

    public void openFavoriteActions(Player p, UUID id, String name) {
        Msg m = plugin.msg;
        List<Menus.Entry> es = new ArrayList<>();
        if (onlineAnywhere(id)) {
            if (plugin.getConfig().getBoolean("tpa.tpa-enabled", true)) {
                es.add(new Menus.Entry(m.text("menus.fav-tpa-button"), null, Material.ENDER_PEARL,
                        () -> quick(p, id, false)));
            }
            if (plugin.getConfig().getBoolean("tpa.tpahere-enabled", true)) {
                es.add(new Menus.Entry(m.text("menus.fav-tpahere-button"), null, Material.ENDER_EYE,
                        () -> quick(p, id, true)));
            }
        }
        es.add(new Menus.Entry(m.text("menus.fav-remove-button"), null, Material.BARRIER, () -> {
            plugin.favorites.remove(p.getUniqueId(), id);
            m.send(p, "fav-removed", "player", name);
            openFavorites(p);
        }));
        plugin.menus.open(p, m.text("menus.fav-actions-title", "player", name), null, es,
                back(() -> openFavorites(p)));
    }

    public void openFavAdd(Player p) {
        Msg m = plugin.msg;
        List<Menus.Entry> es = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.getUniqueId().equals(p.getUniqueId())
                    || plugin.favorites.has(p.getUniqueId(), o.getUniqueId())) continue;
            es.add(new Menus.Entry(m.mm("<yellow>" + o.getName()), null, Material.PLAYER_HEAD, () -> {
                addFavorite(p, o.getUniqueId(), o.getName());
                openFavorites(p);
            }));
        }
        plugin.menus.open(p, m.text("menus.fav-add-title"),
                es.isEmpty() ? m.text("menus.tpa-no-players") : null, es, back(() -> openFavorites(p)));
    }

    public void addFavorite(Player p, UUID id, String name) {
        Msg m = plugin.msg;
        if (id.equals(p.getUniqueId())) {
            m.send(p, "fav-self");
            return;
        }
        int max = plugin.getConfig().getInt("favorites.max", 20);
        switch (plugin.favorites.add(p.getUniqueId(), id, max)) {
            case 1 -> m.send(p, "fav-already", "player", name);
            case 2 -> m.send(p, "fav-max", "max", String.valueOf(max));
            default -> m.send(p, "fav-added", "player", name);
        }
    }

    public void openStatus(Player p) {
        Msg m = plugin.msg;
        boolean econ = plugin.economy.enabled();
        String money = econ ? plugin.economy.format(plugin.economy.get(p.getUniqueId())) : "";
        String play = Dur.format(TpaManager.playMinutes(p) * 60_000L);
        String homes = plugin.homes.all(p.getUniqueId()).size() + "/" + plugin.homes.limitText(p);
        List<String> lines = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("menus.status-lines")) {
            if (!econ && line.contains("{money}")) continue;
            lines.add(line.replace("{player}", p.getName()).replace("{money}", money)
                    .replace("{playtime}", play).replace("{homes}", homes));
        }
        plugin.menus.open(p, m.text("menus.status-title"), m.mm(String.join("\n", lines)), List.of(), null);
    }

    public void openIncoming(Player target, String fromName, boolean here) {
        Msg m = plugin.msg;
        List<Menus.Entry> entries = new ArrayList<>();
        entries.add(new Menus.Entry(m.text("menus.accept-button"), null, Material.LIME_CONCRETE,
                () -> plugin.tpa.accept(target, fromName)));
        entries.add(new Menus.Entry(m.text("menus.deny-button"), null, Material.RED_CONCRETE,
                () -> plugin.tpa.deny(target, fromName)));
        plugin.menus.open(target, m.text("menus.incoming-title"),
                m.text(here ? "menus.incoming-here" : "menus.incoming-to-you", "player", fromName),
                entries, null);
    }

    private Component state(String path, boolean on) {
        FileConfiguration c = plugin.getConfig();
        String raw = c.getString(path, "{state}").replace("{state}",
                c.getString(on ? "menus.state-on" : "menus.state-off", ""));
        return plugin.msg.mm(raw);
    }

    /** Main menu: same buttons as the Esc menu (esc-menu.buttons). Good for Bedrock and NPCs. */
    public void openMain(Player p) {
        Msg m = plugin.msg;
        List<Menus.Entry> es = new ArrayList<>();
        for (Map<?, ?> b : plugin.getConfig().getMapList("esc-menu.buttons")) {
            Object label = b.get("label");
            Object cmd = b.get("command");
            if (label == null || cmd == null) continue;
            if (!plugin.featureOn(String.valueOf(b.get("requires")))) continue;
            String command = String.valueOf(cmd);
            es.add(new Menus.Entry(m.mm(String.valueOf(label)), null, Material.PAPER, () -> p.performCommand(command)));
        }
        plugin.menus.open(p, m.text("menus.main-title"), m.text("menus.main-info"), es, null);
    }

    public void openSettings(Player p) {
        Msg m = plugin.msg;
        UUID id = p.getUniqueId();
        List<Menus.Entry> es = new ArrayList<>();
        if (plugin.getConfig().getBoolean("settings.night-vision", true)
                && p.hasPermission("smpcore.settings.nightvision")) {
            es.add(new Menus.Entry(state("menus.nightvision-button", plugin.settings.nightVision(id)), null,
                    Material.GOLDEN_CARROT, () -> {
                plugin.settings.toggleNightVision(p);
                openSettings(p);
            }));
        }
        es.add(new Menus.Entry(state("menus.tpa-requests-button", plugin.settings.acceptsTpa(id)), null,
                Material.ENDER_PEARL, () -> {
            plugin.settings.toggleTpa(id);
            openSettings(p);
        }));
        plugin.menus.open(p, m.text("menus.settings-title"), null, es, null);
    }

    public void openPay(Player p) {
        Msg m = plugin.msg;
        List<Menus.Entry> es = new ArrayList<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.getUniqueId().equals(p.getUniqueId())) continue;
            es.add(new Menus.Entry(m.mm("<yellow>" + o.getName()), null, Material.PLAYER_HEAD,
                    () -> openPayAmount(p, o)));
        }
        plugin.menus.open(p, m.text("menus.pay-title"),
                m.text(es.isEmpty() ? "menus.tpa-no-players" : "menus.pay-info"), es, null);
    }

    public void openPayAmount(Player p, Player target) {
        Msg m = plugin.msg;
        List<Double> presets = plugin.getConfig().getDoubleList("economy.pay-presets");
        if (presets.isEmpty()) presets = List.of(100.0, 1000.0, 10000.0, 100000.0);
        String btn = plugin.getConfig().getString("menus.pay-button", "<green>Pay {amount}");
        List<Menus.Entry> es = new ArrayList<>();
        for (double amt : presets) {
            es.add(new Menus.Entry(m.mm(btn.replace("{amount}", plugin.economy.format(amt))), null,
                    Material.GOLD_INGOT, () -> plugin.economy.pay(p, target.getUniqueId(), target.getName(), amt)));
        }
        plugin.menus.open(p, m.text("menus.pay-amount-title", "player", target.getName()), null, es,
                back(() -> openPay(p)));
    }

    public void openRtp(Player p) {
        Msg m = plugin.msg;
        if (!plugin.rtp.rtpOpen()) {
            m.send(p, "rtp-closed");
            return;
        }
        var ws = plugin.getConfig().getConfigurationSection("rtp.worlds");
        List<Menus.Entry> es = new ArrayList<>();
        if (ws != null) {
            for (String key : ws.getKeys(false)) {
                var sec = ws.getConfigurationSection(key);
                if (sec == null || !sec.getBoolean("enabled", true)) continue;
                Material icon = Material.matchMaterial(sec.getString("icon", "GRASS_BLOCK"));
                if (icon == null) icon = Material.PAPER;
                Component label = m.mm(sec.getString("color", "<green>") + sec.getString("name", key));
                double price = sec.getDouble("price", 0);
                String tip = "<gray>Distance: " + sec.getInt("min-radius", 500) + " - " + sec.getInt("max-radius", 5000)
                        + (price > 0 && plugin.economy.enabled() ? " <gold>Price: " + plugin.economy.format(price) : "");
                es.add(new Menus.Entry(label, m.mm(tip), icon, () -> plugin.rtp.start(p, key)));
            }
        }
        if (es.isEmpty()) {
            m.send(p, "rtp-closed");
            return;
        }
        plugin.menus.open(p, m.text("menus.rtp-title"), m.text("menus.rtp-info"), es, null);
    }

    public void openRtpQueue(Player p) {
        Msg m = plugin.msg;
        if (!plugin.rtp.queueOpen()) {
            m.send(p, "rtpq-closed");
            return;
        }
        boolean queued = plugin.rtp.inQueue(p.getUniqueId());
        List<Menus.Entry> es = new ArrayList<>();
        if (queued) {
            es.add(new Menus.Entry(m.text("menus.rtpq-leave-button"), null, Material.RED_CONCRETE,
                    () -> plugin.rtp.leaveQueue(p)));
        } else {
            es.add(new Menus.Entry(m.text("menus.rtpq-join-button"), null, Material.LIME_CONCRETE,
                    () -> plugin.rtp.joinQueue(p)));
        }
        plugin.menus.open(p, m.text("menus.rtpq-title"),
                m.text("menus.rtpq-info", "count", String.valueOf(plugin.rtp.queueSize()),
                        "need", String.valueOf(plugin.rtp.queueNeeded())), es, null);
    }
}
