package com.smpcore;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.List;

/** Bedrock players get native forms, Java players get dialogs (or chest menus on old versions). */
public final class MenuRouter implements Menus {
    private final Menus java;
    private final BedrockMenus bedrock;

    public MenuRouter(Menus java, BedrockMenus bedrock) {
        this.java = java;
        this.bedrock = bedrock;
    }

    @Override
    public void open(Player p, Component title, Component message, List<Entry> entries, Entry back) {
        if (bedrock != null && bedrock.isBedrock(p)) bedrock.open(p, title, message, entries, back);
        else java.open(p, title, message, entries, back);
    }
}
