package com.smpcore;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Lets EconomyShopGUI and other Vault plugins use the DiamondCraft economy. */
public final class VaultEconomy implements Economy {
    private final SMPCore plugin;

    public VaultEconomy(SMPCore plugin) {
        this.plugin = plugin;
    }

    private EconomyManager em() {
        return plugin.economy;
    }

    private UUID id(String name) {
        return plugin.players.idFor(name);
    }

    private EconomyResponse withdraw(UUID id, double amt) {
        if (amt < 0) return new EconomyResponse(0, em().get(id), EconomyResponse.ResponseType.FAILURE, "Negative amount");
        if (!em().withdraw(id, amt)) {
            return new EconomyResponse(0, em().get(id), EconomyResponse.ResponseType.FAILURE, "Insufficient funds");
        }
        return new EconomyResponse(amt, em().get(id), EconomyResponse.ResponseType.SUCCESS, null);
    }

    private EconomyResponse deposit(UUID id, double amt) {
        if (amt < 0) return new EconomyResponse(0, em().get(id), EconomyResponse.ResponseType.FAILURE, "Negative amount");
        em().deposit(id, amt);
        return new EconomyResponse(amt, em().get(id), EconomyResponse.ResponseType.SUCCESS, null);
    }

    private EconomyResponse noBank() {
        return new EconomyResponse(0, 0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Banks are not supported");
    }

    @Override public boolean isEnabled() { return em().enabled(); }
    @Override public String getName() { return "DiamondCraft"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }
    @Override public String format(double amount) { return em().format(amount); }
    @Override public String currencyNamePlural() { return plugin.getConfig().getString("economy.currency-plural", "dollars"); }
    @Override public String currencyNameSingular() { return plugin.getConfig().getString("economy.currency-single", "dollar"); }

    @Override public boolean hasAccount(String name) { return true; }
    @Override public boolean hasAccount(OfflinePlayer p) { return true; }
    @Override public boolean hasAccount(String name, String world) { return true; }
    @Override public boolean hasAccount(OfflinePlayer p, String world) { return true; }

    @Override public double getBalance(String name) { return em().get(id(name)); }
    @Override public double getBalance(OfflinePlayer p) { return em().get(p.getUniqueId()); }
    @Override public double getBalance(String name, String world) { return em().get(id(name)); }
    @Override public double getBalance(OfflinePlayer p, String world) { return em().get(p.getUniqueId()); }

    @Override public boolean has(String name, double amt) { return em().has(id(name), amt); }
    @Override public boolean has(OfflinePlayer p, double amt) { return em().has(p.getUniqueId(), amt); }
    @Override public boolean has(String name, String world, double amt) { return em().has(id(name), amt); }
    @Override public boolean has(OfflinePlayer p, String world, double amt) { return em().has(p.getUniqueId(), amt); }

    @Override public EconomyResponse withdrawPlayer(String name, double amt) { return withdraw(id(name), amt); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, double amt) { return withdraw(p.getUniqueId(), amt); }
    @Override public EconomyResponse withdrawPlayer(String name, String world, double amt) { return withdraw(id(name), amt); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, String world, double amt) { return withdraw(p.getUniqueId(), amt); }

    @Override public EconomyResponse depositPlayer(String name, double amt) { return deposit(id(name), amt); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, double amt) { return deposit(p.getUniqueId(), amt); }
    @Override public EconomyResponse depositPlayer(String name, String world, double amt) { return deposit(id(name), amt); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, String world, double amt) { return deposit(p.getUniqueId(), amt); }

    @Override public EconomyResponse createBank(String name, String player) { return noBank(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return noBank(); }
    @Override public EconomyResponse deleteBank(String name) { return noBank(); }
    @Override public EconomyResponse bankBalance(String name) { return noBank(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBank(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBank(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBank(); }
    @Override public EconomyResponse isBankOwner(String name, String playerName) { return noBank(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return noBank(); }
    @Override public EconomyResponse isBankMember(String name, String playerName) { return noBank(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return noBank(); }
    @Override public List<String> getBanks() { return Collections.emptyList(); }

    @Override public boolean createPlayerAccount(String name) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer p) { return true; }
    @Override public boolean createPlayerAccount(String name, String world) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer p, String world) { return true; }
}
