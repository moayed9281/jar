package com.smpcore;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

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
}
