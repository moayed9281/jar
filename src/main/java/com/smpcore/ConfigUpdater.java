package com.smpcore;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

/**
 * Adds settings that are missing from an existing config.yml (after a plugin update)
 * without changing anything the server owner already set. A backup is made first.
 */
public final class ConfigUpdater {
    private ConfigUpdater() { }

    public static void update(File dataFolder, InputStream defaultsStream, Consumer<String> log) {
        File file = new File(dataFolder, "config.yml");
        if (!file.exists() || defaultsStream == null) return;
        try (InputStream in = defaultsStream) {
            YamlConfiguration user = YamlConfiguration.loadConfiguration(file);
            YamlConfiguration def = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
            user.options().parseComments(true);

            List<String> added = new ArrayList<>();
            for (String key : def.getKeys(true)) {
                if (def.isConfigurationSection(key)) continue;
                if (user.contains(key, true)) continue;
                // Lists you edit yourself and entries you may have deleted on purpose are never re-added.
                if (key.startsWith("homes.group-limits.") && user.isConfigurationSection("homes.group-limits")) continue;
                if (key.startsWith("rtp.worlds.")) {
                    String[] parts = key.split("\\.");
                    if (parts.length > 3 && user.isConfigurationSection("rtp.worlds")
                            && !user.isConfigurationSection("rtp.worlds." + parts[2])) continue;
                }
                user.set(key, def.get(key));
                List<String> comments = def.getComments(key);
                if (!comments.isEmpty()) user.setComments(key, comments);
                added.add(key);
            }
            if (added.isEmpty()) return;

            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
            File backup = new File(dataFolder, "config-backup-" + stamp + ".yml");
            Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            user.save(file);
            log.accept("config.yml updated: added " + added.size() + " missing setting(s); backup saved as "
                    + backup.getName());
        } catch (IOException | RuntimeException e) {
            log.accept("Could not update config.yml (your file was not changed): " + e.getMessage());
        }
    }
}
