package com.smpcore;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

public interface Menus {
    record Entry(Component label, Component tooltip, Material icon, Runnable action) { }

    /** message and back may be null. */
    void open(Player p, Component title, Component message, List<Entry> entries, Entry back);
}
