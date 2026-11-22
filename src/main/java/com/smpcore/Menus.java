package com.smpcore;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

public interface Menus {
    record Entry(Component label, Component tooltip, Material icon, Runnable action) { }

    /** message and back may be null. */
    void open(Player p, Component title, Component message, List<Entry> entries, Entry back);

    /** Asks for a short text. onSubmit gets what was typed (may be empty); onBack runs if the player cancels (may be null). */
    void input(Player p, Component title, Component label, String initial, int maxLength,
               java.util.function.Consumer<String> onSubmit, Runnable onBack);
}
