package com.smpcore;

import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Locale;

/** /mute: a muted player can't chat, can't use private-message commands and (optionally) can't see chat. */
public final class MuteListener implements Listener {
    private final SMPCore plugin;

    public MuteListener(SMPCore plugin) {
        this.plugin = plugin;
    }

    private PunishManager.Rec mute(Player p) {
        return plugin.punish.find(p.getUniqueId(), p.getName(), PunishManager.Type.MUTE);
    }

    private String left(PunishManager.Rec r) {
        return r.until() == 0 ? plugin.getConfig().getString("punishments.permanent-text", "Permanent")
                : Dur.format(r.until() - System.currentTimeMillis());
    }

    private void tell(Player p, PunishManager.Rec r) {
        p.sendMessage(plugin.msg.prefixed("mute-blocked", "reason", r.reason(), "duration", left(r)));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        PunishManager.Rec r = mute(p);
        if (r != null) {
            e.setCancelled(true);
            tell(p, r);
            return;
        }
        if (plugin.getConfig().getBoolean("mute.hide-chat", true) && plugin.punish.anyActive(PunishManager.Type.MUTE)) {
            e.viewers().removeIf(a -> a instanceof Player v && mute(v) != null);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        PunishManager.Rec r = mute(p);
        if (r == null) return;
        String name = e.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(colon + 1);
        for (String x : plugin.getConfig().getStringList("mute.blocked-commands")) {
            if (x.equalsIgnoreCase(name)) {
                e.setCancelled(true);
                tell(p, r);
                return;
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            PunishManager.Rec r = p.isOnline() ? mute(p) : null;
            if (r != null) tell(p, r);
        }, 20L);
    }
}
