package com.smpcore;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * /serverrestart <minutes>: announces the restart every 5 minutes, counts down the last seconds,
 * then kicks everyone and restarts (or stops) the server.
 */
public final class RestartManager {
    private final SMPCore plugin;
    private BukkitTask task;
    private long endAt;
    private long lastLeft;

    public RestartManager(SMPCore plugin) {
        this.plugin = plugin;
    }

    public boolean running() {
        return task != null;
    }

    private static String time(long seconds) {
        if (seconds >= 60 && seconds % 60 == 0) {
            long m = seconds / 60;
            return m + (m == 1 ? " minute" : " minutes");
        }
        return seconds + (seconds == 1 ? " second" : " seconds");
    }

    public long secondsLeft() {
        return Math.max(0, (long) Math.ceil((endAt - System.currentTimeMillis()) / 1000.0));
    }

    public void schedule(CommandSender by, int minutes) {
        boolean replaced = task != null;
        if (task != null) task.cancel();
        endAt = System.currentTimeMillis() + minutes * 60_000L;
        lastLeft = minutes * 60L;
        announceMinutes(lastLeft);
        plugin.getLogger().info("[Restart] " + by.getName() + " scheduled a server restart in " + time(lastLeft)
                + (replaced ? " (replacing the earlier one)" : ""));
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        plugin.msg.send(by, "restart-scheduled", "time", time(lastLeft));
    }

    public void cancel(CommandSender by) {
        if (task == null) {
            plugin.msg.send(by, "restart-none");
            return;
        }
        task.cancel();
        task = null;
        Bukkit.broadcast(plugin.msg.prefixed("restart-cancelled"));
        plugin.getLogger().info("[Restart] " + by.getName() + " cancelled the server restart");
    }

    public void status(CommandSender to) {
        if (task == null) plugin.msg.send(to, "restart-usage");
        else plugin.msg.send(to, "restart-status", "time", time(secondsLeft()));
    }

    private void tick() {
        long left = secondsLeft();
        int period = Math.max(1, plugin.getConfig().getInt("restart.announce-every-minutes", 5)) * 60;
        int countdown = Math.max(0, plugin.getConfig().getInt("restart.countdown-seconds", 5));
        // go through every second since the last tick, so a laggy server never skips an announcement
        for (long s = lastLeft - 1; s >= left && s > 0; s--) {
            if (s <= countdown) announceSeconds(s);
            else if (s % period == 0) announceMinutes(s);
        }
        lastLeft = left;
        if (left <= 0) {
            task.cancel();
            task = null;
            restartNow();
        }
    }

    private void announceMinutes(long seconds) {
        Bukkit.broadcast(plugin.msg.prefixed("restart-announce", "time", time(seconds)));
        ping(1.0f);
    }

    private void announceSeconds(long seconds) {
        Bukkit.broadcast(plugin.msg.prefixed("restart-seconds", "seconds", String.valueOf(seconds)));
        ping(seconds == 1 ? 2.0f : 1.4f);
    }

    private void ping(float pitch) {
        for (Player p : Bukkit.getOnlinePlayers()) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, pitch);
    }

    private void restartNow() {
        Bukkit.broadcast(plugin.msg.prefixed("restart-now"));
        Component kick = plugin.msg.get("restart-kick");
        for (Player p : Bukkit.getOnlinePlayers()) p.kick(kick);
        boolean stop = "stop".equalsIgnoreCase(plugin.getConfig().getString("restart.method", "restart"));
        plugin.getLogger().info("[Restart] restarting now (" + (stop ? "stop" : "restart") + ")");
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (stop) Bukkit.shutdown();
            else Bukkit.spigot().restart();
        }, 20L);
    }
}
