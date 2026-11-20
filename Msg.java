package com.smpcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

import java.text.SimpleDateFormat;
import java.util.Date;

public final class Msg {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final SMPCore plugin;

    public Msg(SMPCore plugin) {
        this.plugin = plugin;
    }

    public Component mm(String raw) {
        return MM.deserialize(raw);
    }

    /** Read a string or list from config by full path and fill {placeholders}. */
    public Component text(String path, String... kv) {
        FileConfiguration cfg = plugin.getConfig();
        String raw = cfg.isList(path) ? String.join("\n", cfg.getStringList(path))
                : cfg.getString(path, "<red>Missing config: " + path);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            raw = raw.replace("{" + kv[i] + "}", MM.escapeTags(kv[i + 1]));
        }
        return MM.deserialize(raw);
    }

    public Component get(String key, String... kv) {
        return text("messages." + key, kv);
    }

    public void send(CommandSender to, String key, String... kv) {
        to.sendMessage(text("prefix").append(get(key, kv)));
    }

    public Component prefixed(String key, String... kv) {
        return text("prefix").append(get(key, kv));
    }

    public Component screen(String key, PunishManager.Rec r) {
        FileConfiguration cfg = plugin.getConfig();
        String perm = cfg.getString("punishments.permanent-text", "Permanent");
        String dur = perm;
        String exp = perm;
        if (r.until() != 0) {
            dur = Dur.format(r.until() - System.currentTimeMillis());
            exp = new SimpleDateFormat(cfg.getString("punishments.date-format", "yyyy-MM-dd HH:mm z"))
                    .format(new Date(r.until()));
        }
        return text("screens." + key, "player", r.name(), "reason", r.reason(),
                "staff", r.staff(), "duration", dur, "expires", exp,
                "id", r.punishId() == null ? "" : r.punishId());
    }
}
