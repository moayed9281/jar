package com.smpcore;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.plugin.ServicePriority;

/** Only touched when the Vault plugin is installed. */
final class VaultHook {
    private VaultHook() { }

    static Object register(SMPCore plugin) {
        VaultEconomy eco = new VaultEconomy(plugin);
        plugin.getServer().getServicesManager().register(Economy.class, eco, plugin, ServicePriority.Highest);
        return eco;
    }

    static void unregister(SMPCore plugin, Object provider) {
        plugin.getServer().getServicesManager().unregister(Economy.class, provider);
    }
}
