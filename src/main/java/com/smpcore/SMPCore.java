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
    public EconomyManager economy;
    public SettingsManager settings;
    public FavoritesManager favorites;
    public RtpManager rtp;
    private Object vaultProvider;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigUpdater.update(getDataFolder(), getResource("config.yml"), msg -> getLogger().info(msg));
        msg = new Msg(this);
        homes = new HomeManager(this);
        tpa = new TpaManager(this);
        floodgate = loadFloodgate();
        players = new Players(this);
        economy = new EconomyManager(this);
        settings = new SettingsManager(this);
        favorites = new FavoritesManager(this);
        rtp = new RtpManager(this);
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
        cmd(cmds, "balance", "smpcore.economy", "bal", "money");
        cmd(cmds, "pay", "smpcore.economy");
        cmd(cmds, "baltop", "smpcore.economy");
        cmd(cmds, "eco", "smpcore.eco");
        cmd(cmds, "settings", "smpcore.settings");
        cmd(cmds, "menu", "smpcore.menu", "dcmenu");
        cmd(cmds, "status", "smpcore.status", "stats");
        cmd(cmds, "tpmenu", "smpcore.tpa");
        cmd(cmds, "tpfav", "smpcore.tpa", "friends", "fav");
        cmd(cmds, "rtp", "smpcore.rtp");
        cmd(cmds, "rtpq", "smpcore.rtpq", "rtpqueue");
        cmd(cmds, "smpcore", "smpcore.reload", "diamondcraft");
        getServer().getPluginManager().registerEvents(new Listeners(this), this);
        getLogger().info("Menus: " + menus.getClass().getSimpleName());
        applyEconomy();
        getServer().getScheduler().runTaskTimer(this,
                () -> getServer().getOnlinePlayers().forEach(homes::limit), 20L, 600L);
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                new PapiHook(this).register();
                getLogger().info("PlaceholderAPI placeholders registered: %diamondcraft_...%");
            } catch (Throwable t) {
                getLogger().warning("PlaceholderAPI hook failed: " + t);
            }
        }
    }

    @Override
    public void onDisable() {
        if (economy != null) economy.save();
    }

    /** Used by menu buttons: requires: economy | rtp | rtpq */
    public boolean featureOn(String requires) {
        return switch (requires) {
            case "economy" -> economy.enabled();
            case "rtp" -> rtp.rtpOpen();
            case "rtpq" -> rtp.queueOpen();
            default -> true;
        };
    }

    /** Registers/unregisters the economy with Vault (so EconomyShopGUI can use it). */
    public void applyEconomy() {
        boolean want = economy.enabled();
        try {
            if (want && vaultProvider == null) {
                if (getServer().getPluginManager().getPlugin("Vault") != null) {
                    vaultProvider = VaultHook.register(this);
                    getLogger().info("DiamondCraft economy registered with Vault.");
                } else {
                    getLogger().warning("Vault not found: EconomyShopGUI cannot use the DiamondCraft economy. Install Vault.");
                }
            } else if (!want && vaultProvider != null) {
                VaultHook.unregister(this, vaultProvider);
                vaultProvider = null;
                getLogger().info("Economy disabled and removed from Vault.");
            }
        } catch (Throwable t) {
            getLogger().warning("Vault hook failed: " + t);
        }
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
                {"smpcore.bypass.cooldown", PermissionDefault.OP},
                {"smpcore.bypass.playtime", PermissionDefault.OP}, {"smpcore.bypass.tpablock", PermissionDefault.OP},
                {"smpcore.economy", PermissionDefault.TRUE}, {"smpcore.eco", PermissionDefault.OP},
                {"smpcore.settings", PermissionDefault.TRUE}, {"smpcore.settings.nightvision", PermissionDefault.TRUE},
                {"smpcore.menu", PermissionDefault.TRUE}, {"smpcore.status", PermissionDefault.TRUE},
                {"smpcore.rtp", PermissionDefault.TRUE}, {"smpcore.rtpq", PermissionDefault.TRUE}, {"smpcore.homes.unlimited", PermissionDefault.FALSE}
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
        applyEconomy();
    }
}
