package com.smpcore;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Minecraft pause-menu style button screens. Needs Paper 1.21.6+. */
public final class DialogMenus implements Menus {
    private final SMPCore plugin;

    public DialogMenus(SMPCore plugin) {
        this.plugin = plugin;
    }

    private ActionButton button(Entry e) {
        return ActionButton.create(e.label(), e.tooltip(), 200,
                DialogAction.customClick(
                        (view, audience) -> Bukkit.getScheduler().runTask(plugin, e.action()),
                        ClickCallback.Options.builder()
                                .uses(ClickCallback.UNLIMITED_USES)
                                .lifetime(Duration.ofMinutes(10))
                                .build()));
    }

    @Override
    public void open(Player p, Component title, Component message, List<Entry> entries, Entry back) {
        List<ActionButton> buttons = new ArrayList<>();
        for (Entry e : entries) buttons.add(button(e));
        ActionButton exit = back != null ? button(back)
                : ActionButton.create(plugin.msg.text("menus.close-button"), null, 200, null);

        DialogBase.Builder base = DialogBase.builder(title).canCloseWithEscape(true);
        if (message != null) base.body(List.of(DialogBody.plainMessage(message)));

        Dialog dialog = Dialog.create(f -> f.empty()
                .base(base.build())
                .type(buttons.isEmpty()
                        ? DialogType.notice(exit)
                        : DialogType.multiAction(buttons, exit, buttons.size() > 8 ? 2 : 1)));
        p.showDialog(dialog);
    }
}
