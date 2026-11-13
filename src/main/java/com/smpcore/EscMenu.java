package com.smpcore;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.registry.RegistryKey;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import io.papermc.paper.registry.event.RegistryEvents;
import io.papermc.paper.registry.keys.DialogKeys;
import io.papermc.paper.registry.keys.tags.DialogTagKeys;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Adds a "DiamondCraft" button to the Esc menu (needs a server restart after config changes). */
final class EscMenu {
    private EscMenu() { }

    private static YamlConfiguration loadConfig(BootstrapContext ctx) {
        File f = new File(ctx.getDataDirectory().toFile(), "config.yml");
        if (f.exists()) return YamlConfiguration.loadConfiguration(f);
        try (InputStream in = EscMenu.class.getResourceAsStream("/config.yml")) {
            if (in != null) return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
        return new YamlConfiguration();
    }

    static void register(BootstrapContext ctx) {
        YamlConfiguration cfg = loadConfig(ctx);
        if (!cfg.getBoolean("esc-menu.enabled", true)) return;
        MiniMessage mm = MiniMessage.miniMessage();
        Component title = mm.deserialize(cfg.getString("esc-menu.title", "<aqua><bold>DiamondCraft"));
        Component body = mm.deserialize(cfg.getString("esc-menu.body", "<gray>Homes and teleport requests"));
        Key key = Key.key("diamondcraft", "menu");

        List<ActionButton> buttons = new ArrayList<>();
        for (Map<?, ?> m : cfg.getMapList("esc-menu.buttons")) {
            Object label = m.get("label");
            Object command = m.get("command");
            if (label == null || command == null) continue;
            buttons.add(ActionButton.create(mm.deserialize(String.valueOf(label)), null, 200,
                    DialogAction.staticAction(ClickEvent.runCommand(String.valueOf(command)))));
        }
        if (buttons.isEmpty()) return;
        ActionButton close = ActionButton.create(
                mm.deserialize(cfg.getString("menus.close-button", "<gray>Close")), null, 200, null);

        ctx.getLifecycleManager().registerEventHandler(RegistryEvents.DIALOG.compose().newHandler(event -> {
            try {
                event.registry().register(DialogKeys.create(key), b -> b
                        .base(DialogBase.builder(title)
                                .externalTitle(title)
                                .canCloseWithEscape(true)
                                .body(List.of(DialogBody.plainMessage(body)))
                                .build())
                        .type(DialogType.multiAction(buttons, close, 1)));
            } catch (Throwable t) {
                ctx.getLogger().warn("Esc menu dialog could not be registered: " + t);
            }
        }));

        ctx.getLifecycleManager().registerEventHandler(LifecycleEvents.TAGS.postFlatten(RegistryKey.DIALOG), event -> {
            try {
                event.registrar().addToTag(DialogTagKeys.PAUSE_SCREEN_ADDITIONS, Set.of(DialogKeys.create(key)));
            } catch (Throwable t) {
                ctx.getLogger().warn("Esc menu tag could not be set: " + t);
            }
        });
    }
}
