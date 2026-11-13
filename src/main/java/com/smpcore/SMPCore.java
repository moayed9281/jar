package com.smpcore;

import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class SMPCore extends JavaPlugin {
    public Msg msg;
    public HomeManager homes;
    public TpaManager tpa;
    public PunishManager punish;
    public Tp tp;
    public Menus menus;
    public Gui gui;
    public Players players;
    public FloodgateHook floodgate;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        msg = new Msg(this);
        homes = new HomeManager(this);
        tpa = new TpaManager(this);
        floodgate = loadFloodgate();
        players = new Players(this);
        punish = new PunishManager(this);
        tp = new Tp(this);
        menus = createMenus();
        gui = new Gui(this);

        registerPermissions();
        Cmds cmds = new Cmds(this);
        cmd(cmds, "sethome", "smpcore.home");
        cmd(cmds, "home", "smpcore.home");
        cmd(cmds, "homes", "smpcore.home");
        cmd(cmds, "delhome", "smpcore.home");
        cmd(cmds, "tpa", "smpcore.tpa");
        cmd(cmds, "tpahere", "smpcore.tpa");
        cmd(cmds, "tpaccept", "smpcore.tpa", "tpyes");
        cmd(cmds, "tpdeny", "smpcore.tpa", "tpno");
        cmd(cmds, "tpacancel", "smpcore.tpa");
        cmd(cmds, "ban", "smpcore.ban");
        cmd(cmds, "unban", "smpcore.unban");
        cmd(cmds, "kick", "smpcore.kick");
        cmd(cmds, "timeout", "smpcore.timeout");
        cmd(cmds, "untimeout", "smpcore.timeout");
        cmd(cmds, "smpcore", "smpcore.reload", "diamondcraft");
        getServer().getPluginManager().registerEvents(new Listeners(this), this);
        getLogger().info("Menus: " + menus.getClass().getSimpleName());
    }

    private void cmd(Cmds cmds, String name, String perm, String... aliases) {
        getServer().getCommandMap().register("diamondcraft",
                new DynCommand(name, perm, List.of(aliases), cmds));
    }

    private void registerPermissions() {
        Object[][] perms = {
                {"smpcore.home", PermissionDefault.TRUE}, {"smpcore.tpa", PermissionDefault.TRUE},
                {"smpcore.ban", PermissionDefault.OP}, {"smpcore.unban", PermissionDefault.OP},
                {"smpcore.kick", PermissionDefault.OP}, {"smpcore.timeout", PermissionDefault.OP},
                {"smpcore.reload", PermissionDefault.OP}, {"smpcore.bypass.delay", PermissionDefault.OP},
                {"smpcore.bypass.cooldown", PermissionDefault.OP}, {"smpcore.homes.unlimited", PermissionDefault.FALSE}
        };
        for (Object[] x : perms) {
            try {
                getServer().getPluginManager().addPermission(new Permission((String) x[0], (PermissionDefault) x[1]));
            } catch (IllegalArgumentException ignored) { }
        }
    }

    private FloodgateHook loadFloodgate() {
        if (getServer().getPluginManager().getPlugin("floodgate") == null) return null;
        try {
            FloodgateHook hook = new FloodgateHook();
            getLogger().info("Floodgate found: Bedrock players supported.");
            return hook;
        } catch (Throwable t) {
            getLogger().warning("Floodgate found but API failed to load: " + t);
            return null;
        }
    }

    private Menus createMenus() {
        Menus java = null;
        if (getConfig().getBoolean("menus.use-dialogs", true)) {
            try {
                Class.forName("io.papermc.paper.registry.data.dialog.DialogBase", false, SMPCore.class.getClassLoader());
                java = new DialogMenus(this);
            } catch (Throwable ignored) {
                getLogger().info("Dialogs not available on this version, using chest menus.");
            }
        }
        if (java == null) {
            ChestMenus cm = new ChestMenus(this);
            getServer().getPluginManager().registerEvents(cm, this);
            java = cm;
        }
        BedrockMenus bedrock = null;
        if (floodgate != null && getConfig().getBoolean("menus.use-bedrock-forms", true)) {
            try {
                bedrock = new BedrockMenus(this, floodgate);
            } catch (Throwable t) {
                getLogger().warning("Bedrock forms unavailable: " + t);
            }
        }
        return new MenuRouter(java, bedrock);
    }

    public void reload() {
        reloadConfig();
        homes.load();
    }
}
