package com.smpcore;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Logs every plugin command with its result (OK / FAILED / DENIED / ERROR) to a file, the console (failures) and Discord. */
public final class CommandLog {
    private static final class Pending {
        final String line;
        boolean done;

        Pending(String line) {
            this.line = line;
        }
    }

    private final SMPCore plugin;
    private final File file;
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    public CommandLog(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "logs/commands.log");
        file.getParentFile().mkdirs();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("command-log.enabled", true);
    }

    private boolean excluded(String line) {
        String name = line.substring(1).split(" ")[0];
        for (String x : plugin.getConfig().getStringList("command-log.exclude")) {
            if (x.equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    /** Before a command runs. */
    public void start(CommandSender s, String line) {
        if (!enabled() || excluded(line)) return;
        String who = s.getName();
        Pending p = new Pending(line);
        pending.put(who, p);
        Bukkit.getScheduler().runTaskLater(plugin, () -> finish(who, p, true, "no message"), 100L);
    }

    /** Msg.send reports every message a sender gets; the first one decides OK or FAILED. */
    public void result(CommandSender s, String key, boolean error) {
        Pending p = pending.get(s.getName());
        if (p != null) finish(s.getName(), p, !error, key);
    }

    public void denied(CommandSender s, String line) {
        if (enabled() && !excluded(line) && plugin.getConfig().getBoolean("command-log.log-denied", true)) {
            emit(s.getName(), line, false, "DENIED (no permission)");
        }
    }

    public void error(CommandSender s, String line, Throwable t) {
        Pending p = pending.get(s.getName());
        if (p != null) finish(s.getName(), p, false, "ERROR " + t);
        else if (enabled()) emit(s.getName(), line, false, "ERROR " + t);
    }

    /** Commands that do not come through DynCommand (Discord slash commands). */
    public void external(String who, String line, boolean ok, String result) {
        if (enabled()) emit(who, line, ok, result);
    }

    private synchronized void finish(String who, Pending p, boolean ok, String key) {
        if (p.done) return;
        p.done = true;
        pending.remove(who, p);
        emit(who, p.line, ok, key);
    }

    private void emit(String who, String line, boolean ok, String result) {
        String text = "[" + (ok ? "OK" : "FAILED") + "] " + who + " " + line + " -> " + result;
        if (plugin.getConfig().getBoolean("command-log.file", true)) {
            String row = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()) + " " + text + System.lineSeparator();
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    Files.writeString(file.toPath(), row, StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ignored) { }
            });
        }
        if (!ok) plugin.getLogger().info("[CMD] " + text);
        plugin.discord.logCommand(who, line, ok, result);
    }
}
