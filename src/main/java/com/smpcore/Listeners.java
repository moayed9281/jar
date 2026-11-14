package com.smpcore;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.Bukkit;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class Listeners implements Listener {
    private final SMPCore plugin;

    public Listeners(SMPCore plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        plugin.players.record(e.getName(), e.getUniqueId());
        PunishManager.Rec ban = plugin.punish.get(e.getUniqueId(), PunishManager.Type.BAN);
        if (ban != null) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, plugin.msg.screen("ban", ban));
            return;
        }
        PunishManager.Rec to = plugin.punish.get(e.getUniqueId(), PunishManager.Type.TIMEOUT);
        if (to != null) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, plugin.msg.screen("timeout", to));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        plugin.economy.ensure(e.getPlayer().getUniqueId());
        plugin.settings.applyNightVision(e.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Bukkit.getScheduler().runTask(plugin, () -> plugin.settings.applyNightVision(e.getPlayer()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        plugin.rtp.onQuit(e.getPlayer().getUniqueId());
    }
}
