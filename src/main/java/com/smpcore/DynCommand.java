package com.smpcore;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.List;

/** Command registered in code (paper-plugin.yml has no commands section). */
public final class DynCommand extends Command {
    private final Cmds cmds;

    public DynCommand(String name, String permission, List<String> aliases, Cmds cmds) {
        super(name);
        this.cmds = cmds;
        setPermission(permission);
        setAliases(aliases);
        setDescription("DiamondCraft " + name);
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!testPermission(sender)) return true;
        return cmds.onCommand(sender, this, label, args);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return cmds.onTabComplete(sender, this, alias, args);
    }
}
