package com.smpcore;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.List;

/** Command registered in code (paper-plugin.yml has no commands section). Every run is logged. */
public final class DynCommand extends Command {
    private final SMPCore plugin;
    private final Cmds cmds;

    public DynCommand(SMPCore plugin, String name, String permission, List<String> aliases, Cmds cmds) {
        super(name);
        this.plugin = plugin;
        this.cmds = cmds;
        setPermission(permission);
        setAliases(aliases);
        setDescription("DiamondCraft " + name);
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        String line = "/" + label + (args.length == 0 ? "" : " " + String.join(" ", args));
        if (!testPermission(sender)) {
            plugin.cmdlog.denied(sender, line);
            return true;
        }
        plugin.cmdlog.start(sender, line);
        try {
            return cmds.onCommand(sender, this, label, args);
        } catch (RuntimeException | Error t) {
            plugin.cmdlog.error(sender, line, t);
            throw t;
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return cmds.onTabComplete(sender, this, alias, args);
    }
}
