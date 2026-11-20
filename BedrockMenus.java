package com.smpcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;

import java.util.ArrayList;
import java.util.List;

/** Native Bedrock forms through Floodgate. */
public final class BedrockMenus implements Menus {
    private static final LegacyComponentSerializer LEG = LegacyComponentSerializer.legacySection();
    private final SMPCore plugin;
    private final FloodgateHook hook;

    public BedrockMenus(SMPCore plugin, FloodgateHook hook) {
        this.plugin = plugin;
        this.hook = hook;
    }

    public boolean isBedrock(Player p) {
        return hook.isBedrock(p.getUniqueId());
    }

    @Override
    public void open(Player p, Component title, Component message, List<Entry> entries, Entry back) {
        List<Entry> all = new ArrayList<>(entries);
        if (back != null) all.add(back);
        SimpleForm.Builder b = SimpleForm.builder()
                .title(LEG.serialize(title))
                .content(message == null ? "" : LEG.serialize(message));
        for (Entry e : all) {
            String text = LEG.serialize(e.label());
            if (e.tooltip() != null) text += "\n" + LEG.serialize(e.tooltip());
            b.button(text);
        }
        b.validResultHandler(r -> {
            int i = r.clickedButtonId();
            if (i >= 0 && i < all.size()) Bukkit.getScheduler().runTask(plugin, all.get(i).action());
        });
        hook.send(p.getUniqueId(), b.build());
    }
}
