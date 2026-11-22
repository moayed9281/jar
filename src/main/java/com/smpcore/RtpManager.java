package com.smpcore;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Random teleport (/rtp) and the Donut-style RTP queue (/rtpq). */
public final class RtpManager {
    private static final Set<Material> UNSAFE = Set.of(Material.MAGMA_BLOCK, Material.CAMPFIRE,
            Material.SOUL_CAMPFIRE, Material.CACTUS, Material.POWDER_SNOW, Material.POINTED_DRIPSTONE,
            Material.BEDROCK, Material.TNT);

    private final SMPCore plugin;
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final Set<UUID> searching = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> queue = new LinkedHashSet<>();
    private BukkitTask queueTask;
    private boolean matching;

    public RtpManager(SMPCore plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ switches
    public boolean rtpOpen() {
        return plugin.getConfig().getBoolean("rtp.enabled", true);
    }

    public boolean queueOpen() {
        return rtpOpen() && plugin.getConfig().getBoolean("rtp.queue.enabled", true);
    }

    public List<String> openWorldKeys() {
        List<String> out = new ArrayList<>();
        ConfigurationSection ws = plugin.getConfig().getConfigurationSection("rtp.worlds");
        if (ws == null) return out;
        for (String key : ws.getKeys(false)) {
            if (ws.getBoolean(key + ".enabled", true)) out.add(key);
        }
        return out;
    }

    public boolean inQueue(UUID id) {
        return queue.contains(id);
    }

    public int queueSize() {
        return queue.size();
    }

    public int queueNeeded() {
        return Math.max(2, plugin.getConfig().getInt("rtp.queue.players", 2));
    }

    // ------------------------------------------------------------ cooldown
    private boolean onCooldown(Player p) {
        long cd = plugin.getConfig().getLong("rtp.cooldown", 30) * 1000L;
        Long last = cooldown.get(p.getUniqueId());
        if (cd <= 0 || last == null || p.hasPermission("smpcore.bypass.cooldown")) return false;
        long left = cd - (System.currentTimeMillis() - last);
        if (left <= 0) return false;
        plugin.msg.send(p, "rtp-cooldown", "seconds", String.valueOf(left / 1000 + 1));
        return true;
    }

    // ------------------------------------------------------------ /rtp
    public void start(Player p, String key) {
        Msg m = plugin.msg;
        if (!rtpOpen()) {
            m.send(p, "rtp-closed");
            return;
        }
        ConfigurationSection w = plugin.getConfig().getConfigurationSection("rtp.worlds." + key);
        if (w == null || !w.getBoolean("enabled", true)) {
            m.send(p, "rtp-world-closed");
            return;
        }
        World world = Bukkit.getWorld(w.getString("world", "world"));
        if (world == null) {
            m.send(p, "rtp-no-world");
            return;
        }
        if (onCooldown(p)) return;
        double price = w.getDouble("price", 0);
        boolean charge = price > 0 && plugin.economy.enabled();
        if (charge && !plugin.economy.has(p.getUniqueId(), price)) {
            m.send(p, "rtp-no-money", "amount", plugin.economy.format(price));
            return;
        }
        if (searching.size() >= Math.max(1, plugin.getConfig().getInt("rtp.max-concurrent", 4))) {
            m.send(p, "rtp-busy");
            return;
        }
        if (!searching.add(p.getUniqueId())) {
            m.send(p, "rtp-searching");
            return;
        }
        m.send(p, "rtp-finding");
        String name = w.getString("name", key);
        find(world, w, loc -> {
            searching.remove(p.getUniqueId());
            if (!p.isOnline()) return;
            if (loc == null) {
                m.send(p, "rtp-fail");
                return;
            }
            if (charge && !plugin.economy.withdraw(p.getUniqueId(), price)) {
                m.send(p, "rtp-no-money", "amount", plugin.economy.format(price));
                return;
            }
            if (charge) plugin.economy.logMisc("rtp", "RTP fee", p.getUniqueId(), price, true);
            plugin.tp.go(p, () -> fresh(world, loc), name,
                    () -> {
                        cooldown.put(p.getUniqueId(), System.currentTimeMillis());
                        unstick(p);
                    },
                    charge ? () -> {
                        plugin.economy.deposit(p.getUniqueId(), price);
                        plugin.economy.logMisc("rtp", "RTP refund", p.getUniqueId(), price, false);
                    } : null);
        });
    }

    // ------------------------------------------------------------ /rtpq
    public void joinQueue(Player p) {
        Msg m = plugin.msg;
        if (!queueOpen()) {
            m.send(p, "rtpq-closed");
            return;
        }
        if (queueWorld() == null) {
            m.send(p, "rtp-world-closed");
            return;
        }
        if (queue.contains(p.getUniqueId())) {
            m.send(p, "rtpq-already");
            return;
        }
        if (onCooldown(p)) return;
        queue.add(p.getUniqueId());
        m.send(p, "rtpq-joined");
        ensureTask();
        tryMatch();
    }

    public void leaveQueue(Player p) {
        if (queue.remove(p.getUniqueId())) plugin.msg.send(p, "rtpq-left");
        else plugin.msg.send(p, "rtpq-not-queued");
    }

    public void onQuit(UUID id) {
        queue.remove(id);
        searching.remove(id);
    }

    private ConfigurationSection queueWorld() {
        String key = plugin.getConfig().getString("rtp.queue.world", "overworld");
        ConfigurationSection w = plugin.getConfig().getConfigurationSection("rtp.worlds." + key);
        if (w == null || !w.getBoolean("enabled", true) || Bukkit.getWorld(w.getString("world", "world")) == null) {
            return null;
        }
        return w;
    }

    private void ensureTask() {
        if (queueTask != null) return;
        queueTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            queue.removeIf(id -> Bukkit.getPlayer(id) == null);
            if (queue.isEmpty() || !queueOpen()) {
                queue.clear();
                queueTask.cancel();
                queueTask = null;
                return;
            }
            Component bar = plugin.msg.get("rtpq-searching", "count", String.valueOf(queue.size()),
                    "need", String.valueOf(queueNeeded()));
            for (UUID id : queue) {
                Player q = Bukkit.getPlayer(id);
                if (q != null) q.sendActionBar(bar);
            }
        }, 20L, 20L);
    }

    private void tryMatch() {
        if (matching || queue.size() < queueNeeded()) return;
        matching = true;
        long delay = Math.max(0, plugin.getConfig().getLong("rtp.queue.match-delay", 3));
        for (UUID id : queue) {
            Player q = Bukkit.getPlayer(id);
            if (q != null) plugin.msg.send(q, "rtpq-found", "seconds", String.valueOf(delay));
        }
        Bukkit.getScheduler().runTaskLater(plugin, this::runMatch, delay * 20L);
    }

    private void runMatch() {
        matching = false;
        queue.removeIf(id -> Bukkit.getPlayer(id) == null);
        List<Player> group = new ArrayList<>();
        for (UUID id : queue) {
            Player q = Bukkit.getPlayer(id);
            if (q != null && group.size() < queueNeeded()) group.add(q);
        }
        if (group.size() < queueNeeded()) {
            tryMatch();
            return;
        }
        for (Player q : group) queue.remove(q.getUniqueId());
        ConfigurationSection w = queueWorld();
        World world = w == null ? null : Bukkit.getWorld(w.getString("world", "world"));
        if (world == null) {
            for (Player q : group) plugin.msg.send(q, "rtp-fail");
            return;
        }
        find(world, w, loc -> {
            for (Player q : group) {
                if (!q.isOnline()) continue;
                if (plugin.combat.restricted(q)) {
                    q.sendMessage(plugin.msg.prefixed("combat-blocked", "seconds", String.valueOf(plugin.combat.remaining(q))));
                    continue;
                }
                if (loc == null) {
                    plugin.msg.send(q, "rtp-fail");
                    continue;
                }
                q.teleportAsync(fresh(world, loc));
                unstick(q);
                q.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                plugin.msg.send(q, "rtpq-done");
                cooldown.put(q.getUniqueId(), System.currentTimeMillis());
            }
            tryMatch();
        });
    }

    // ------------------------------------------------------------ safe location search
    private void find(World world, ConfigurationSection w, Consumer<Location> cb) {
        int min = Math.max(0, w.getInt("min-radius", 500));
        int max = Math.max(min + 1, w.getInt("max-radius", 5000));
        int attempts = Math.max(1, plugin.getConfig().getInt("rtp.max-attempts", 40));
        search(world, w.getInt("center-x", 0), w.getInt("center-z", 0), min, max, attempts, cb);
    }

    private void search(World w, int cx, int cz, int min, int max, int left, Consumer<Location> cb) {
        if (left <= 0) {
            cb.accept(null);
            return;
        }
        ThreadLocalRandom r = ThreadLocalRandom.current();
        double angle = r.nextDouble() * Math.PI * 2;
        double dist = Math.sqrt(r.nextDouble() * ((double) max * max - (double) min * min) + (double) min * min);
        int x = cx + (int) Math.round(Math.cos(angle) * dist);
        int z = cz + (int) Math.round(Math.sin(angle) * dist);
        w.getChunkAtAsync(x >> 4, z >> 4).whenComplete((chunk, err) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Location loc = err == null ? check(w, x, z) : null;
                    if (loc != null) {
                        // keep the chunk loaded until the teleport happened (it could unload during the countdown)
                        chunk.addPluginChunkTicket(plugin);
                        Bukkit.getScheduler().runTaskLater(plugin, () -> chunk.removePluginChunkTicket(plugin), 600L);
                        cb.accept(loc);
                    } else {
                        search(w, cx, cz, min, max, left - 1, cb);
                    }
                }));
    }

    private Location check(World w, int x, int z) {
        int y = Integer.MIN_VALUE;
        if (w.getEnvironment() == World.Environment.NETHER) {
            for (int yy = Math.min(120, w.getMaxHeight() - 3); yy >= 32; yy--) {
                if (floor(w, x, yy, z)) {
                    y = yy;
                    break;
                }
            }
        } else {
            int hy = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (floor(w, x, hy, z)) y = hy;
        }
        if (y == Integer.MIN_VALUE) return null;
        if (w.getEnvironment() == World.Environment.NORMAL && !openToSky(w, x, y, z)) {
            return null; // covered by blocks = cave or roof, not the surface
        }
        Location loc = new Location(w, x + 0.5, y + 1, z + 0.5);
        return w.getWorldBorder().isInside(loc) ? loc : null;
    }

    /** Nothing solid (except leaves) above the spot: it is really on the surface, not in a cave. */
    private boolean openToSky(World w, int x, int y, int z) {
        for (int yy = y + 3; yy < w.getMaxHeight(); yy++) {
            Material t = w.getBlockAt(x, yy, z).getType();
            if (t.isSolid() && !t.name().contains("LEAVES")) return false;
        }
        return w.getBlockAt(x, y + 1, z).getLightFromSky() >= 10;
    }

    /** The spot is checked again right before the teleport (the world may have changed since the search). */
    private Location fresh(World w, Location found) {
        Location again = check(w, found.getBlockX(), found.getBlockZ());
        return again != null ? again : found;
    }

    /** A moment after arriving: if the player is stuck inside blocks, move them up to the surface. */
    private void unstick(Player p) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            Location l = p.getLocation();
            World w = l.getWorld();
            int x = l.getBlockX();
            int z = l.getBlockZ();
            if (w.getBlockAt(x, l.getBlockY(), z).isPassable() && w.getBlockAt(x, l.getBlockY() + 1, z).isPassable()) return;
            int y = Integer.MIN_VALUE;
            if (w.getEnvironment() == World.Environment.NETHER) {
                for (int yy = l.getBlockY(); yy < Math.min(120, w.getMaxHeight() - 3); yy++) {
                    if (floor(w, x, yy, z)) {
                        y = yy;
                        break;
                    }
                }
            } else {
                int hy = w.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                if (floor(w, x, hy, z)) y = hy;
            }
            if (y != Integer.MIN_VALUE) {
                p.teleportAsync(new Location(w, x + 0.5, y + 1, z + 0.5, l.getYaw(), l.getPitch()));
            }
        }, 15L);
    }

    private boolean floor(World w, int x, int y, int z) {
        if (y <= w.getMinHeight() || y + 2 >= w.getMaxHeight()) return false;
        Material t = w.getBlockAt(x, y, z).getType();
        if (!t.isSolid() || UNSAFE.contains(t) || t.name().contains("LEAVES")) return false;
        var a = w.getBlockAt(x, y + 1, z);
        var b = w.getBlockAt(x, y + 2, z);
        return a.isPassable() && !a.isLiquid() && b.isPassable() && !b.isLiquid();
    }
}
