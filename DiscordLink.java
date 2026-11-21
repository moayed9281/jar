package com.smpcore;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A Discord bot that runs INSIDE the plugin (no separate server needed).
 * Players run /link in Discord, get a code, then type /link CODE in game.
 */
public final class DiscordLink extends ListenerAdapter {
    public record Link(String discordId, String discordName, long at) { }

    private record Pending(UUID uuid, String mcName, long expires) { }

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final SMPCore plugin;
    private final File file;
    private final SecureRandom random = new SecureRandom();
    private final Map<UUID, Link> links = new HashMap<>();
    private final Set<UUID> rewarded = new HashSet<>();
    private final Set<String> rewardedDiscord = new HashSet<>();
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, long[]> tries = new ConcurrentHashMap<>();
    private final Set<UUID> pendingCmds = new HashSet<>();
    private volatile JDA jda;

    public DiscordLink(SMPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "links.yml");
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection s = yml.getConfigurationSection("links");
        if (s != null) {
            for (String k : s.getKeys(false)) {
                try {
                    links.put(UUID.fromString(k), new Link(s.getString(k + ".discord-id", ""),
                            s.getString(k + ".discord-name", ""), s.getLong(k + ".at")));
                } catch (IllegalArgumentException ignored) { }
            }
        }
        for (String r : yml.getStringList("rewarded")) {
            try {
                rewarded.add(UUID.fromString(r));
            } catch (IllegalArgumentException ignored) { }
        }
        rewardedDiscord.addAll(yml.getStringList("rewarded-discord"));
        for (String r : yml.getStringList("pending-commands")) {
            try {
                pendingCmds.add(UUID.fromString(r));
            } catch (IllegalArgumentException ignored) { }
        }
    }

    // ------------------------------------------------------------ state
    public boolean enabled() {
        var c = plugin.getConfig();
        String token = c.getString("discord.bot-token", "").trim();
        return c.getBoolean("discord.enabled", false) && !token.isEmpty() && !token.startsWith("PUT-");
    }

    public boolean ready() {
        JDA j = jda;
        return j != null && j.getStatus() == JDA.Status.CONNECTED;
    }

    public synchronized Link get(UUID id) {
        return links.get(id);
    }

    private synchronized UUID uuidByDiscord(String discordId) {
        for (Map.Entry<UUID, Link> e : links.entrySet()) {
            if (e.getValue().discordId().equals(discordId)) return e.getKey();
        }
        return null;
    }

    private String guildId() {
        return plugin.getConfig().getString("discord.guild-id", "").trim();
    }

    private void saveLater() {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, this::save);
    }

    synchronized void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, Link> e : links.entrySet()) {
            String p = "links." + e.getKey() + ".";
            yml.set(p + "discord-id", e.getValue().discordId());
            yml.set(p + "discord-name", e.getValue().discordName());
            yml.set(p + "at", e.getValue().at());
        }
        yml.set("rewarded", rewarded.stream().map(UUID::toString).toList());
        yml.set("rewarded-discord", new java.util.ArrayList<>(rewardedDiscord));
        yml.set("pending-commands", pendingCmds.stream().map(UUID::toString).toList());
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Could not save links.yml: " + ex.getMessage());
        }
    }

    // ------------------------------------------------------------ bot lifecycle
    public void start() {
        if (!enabled() || jda != null) return;
        String token = plugin.getConfig().getString("discord.bot-token", "").trim();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                JDA j = JDABuilder.createLight(token, List.of()).addEventListeners(this).build();
                j.awaitReady();
                jda = j;
                registerCommands(j);
                plugin.getLogger().info("Discord bot connected as " + j.getSelfUser().getName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                plugin.getLogger().warning("Discord bot could not start (check bot-token): " + e.getMessage());
            }
        });
    }

    public void stop() {
        JDA j = jda;
        jda = null;
        if (j == null) return;
        j.shutdown();
        try {
            if (!j.awaitShutdown(Duration.ofSeconds(5))) j.shutdownNow();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public void restart() {
        stop();
        start();
    }

    private void registerCommands(JDA j) {
        List<SlashCommandData> cmds = List.of(
                Commands.slash("link", "Link your Minecraft account with the code from the game")
                        .addOption(OptionType.STRING, "code", "The code you got from /link in Minecraft", true),
                Commands.slash("unlink", "Unlink your Minecraft account"),
                Commands.slash("profile", "Show a linked Minecraft profile")
                        .addOption(OptionType.USER, "user", "Another member", false));
        String gid = guildId();
        Guild g = gid.isEmpty() ? null : j.getGuildById(gid);
        if (g != null) {
            g.updateCommands().addCommands(cmds).queue();
        } else {
            if (!gid.isEmpty()) plugin.getLogger().warning("Discord: bot is not in guild " + gid
                    + ", registering global commands (can take up to an hour to show).");
            j.updateCommands().addCommands(cmds).queue();
        }
    }

    private Guild guild() {
        JDA j = jda;
        if (j == null) return null;
        String gid = guildId();
        if (!gid.isEmpty()) return j.getGuildById(gid);
        return j.getGuilds().size() == 1 ? j.getGuilds().get(0) : null;
    }

    // ------------------------------------------------------------ roles / DMs
    private void grantRole(String discordId) {
        changeRole(discordId, true);
    }

    private void removeRole(String discordId) {
        changeRole(discordId, false);
    }

    private void changeRole(String discordId, boolean add) {
        String roleId = plugin.getConfig().getString("discord.linked-role-id", "").trim();
        Guild g = guild();
        if (roleId.isEmpty() || g == null) return;
        Role role = g.getRoleById(roleId);
        if (role == null) {
            plugin.getLogger().warning("Discord: linked-role-id not found in the server.");
            return;
        }
        var action = add ? g.addRoleToMember(UserSnowflake.fromId(discordId), role)
                : g.removeRoleFromMember(UserSnowflake.fromId(discordId), role);
        action.queue(null, err -> plugin.getLogger().warning("Discord role change failed: " + err.getMessage()));
    }

    private void dm(String discordId, String text) {
        JDA j = jda;
        if (j == null) return;
        j.retrieveUserById(discordId).queue(u -> u.openPrivateChannel()
                .queue(ch -> ch.sendMessage(text).queue(null, x -> { }), x -> { }), x -> { });
    }

    // ------------------------------------------------------------ codes
    private String randomCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent e) {
        try {
            String gid = guildId();
            if (!gid.isEmpty() && (e.getGuild() == null || !e.getGuild().getId().equals(gid))) {
                e.reply("This command only works on the official server.").setEphemeral(true).queue();
                return;
            }
            switch (e.getName()) {
                case "link" -> discordLink(e);
                case "unlink" -> discordUnlink(e);
                case "profile" -> discordProfile(e);
                default -> { }
            }
            plugin.cmdlog.external(e.getUser().getName() + " (Discord)", "/" + e.getName(), true, "used");
        } catch (Exception ex) {
            plugin.cmdlog.external(e.getUser().getName() + " (Discord)", "/" + e.getName(), false, "ERROR " + ex);
            plugin.getLogger().warning("Discord command error: " + ex);
        }
    }

    /** Discord: /link <code>  (the code comes from /link in Minecraft). */
    private void discordLink(SlashCommandInteractionEvent e) {
        String did = e.getUser().getId();
        if (blocked(did)) {
            e.reply("Too many wrong codes. Please wait a few minutes.").setEphemeral(true).queue();
            return;
        }
        UUID existing = uuidByDiscord(did);
        if (existing != null) {
            e.reply("Your Discord is already linked to **" + plugin.players.nameFor(existing)
                    + "**. Use /unlink first.").setEphemeral(true).queue();
            return;
        }
        var opt = e.getOption("code");
        String code = opt == null ? "" : opt.getAsString().trim().toUpperCase(Locale.ROOT);
        Pending pend = pending.get(code);
        boolean fromNet = false;
        if (pend == null) {
            pend = netCode(code);
            fromNet = pend != null;
        }
        if (pend == null) {
            fail(did);
            e.reply("That code is not valid. Type /link in Minecraft to get a new one.").setEphemeral(true).queue();
            return;
        }
        if (pend.expires() < System.currentTimeMillis()) {
            pending.remove(code, pend);
            e.reply("That code has expired. Type /link in Minecraft to get a new one.").setEphemeral(true).queue();
            return;
        }
        if (!fromNet && !pending.remove(code, pend)) {
            e.reply("That code is not valid.").setEphemeral(true).queue();
            return;
        }
        if (plugin.net != null) plugin.net.remove("code", code);
        Link l = new Link(did, e.getUser().getName(), System.currentTimeMillis());
        boolean ok;
        synchronized (this) {
            ok = uuidByDiscord(did) == null && !links.containsKey(pend.uuid());
            if (ok) links.put(pend.uuid(), l);
        }
        if (!ok) {
            e.reply("That Minecraft account is already linked.").setEphemeral(true).queue();
            return;
        }
        netPutLink(pend.uuid(), l);
        saveLater();
        logLink(pend.mcName() + " linked with Discord account " + e.getUser().getName());
        e.reply("Linked to Minecraft account **" + pend.mcName() + "**!").setEphemeral(true).queue();
        grantRole(did);
        UUID id = pend.uuid();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player on = Bukkit.getPlayer(id);
            if (on != null) plugin.msg.send(on, "link-success", "discord", l.discordName());
            reward(id, l.discordId(), on);
        });
    }

    private void discordUnlink(SlashCommandInteractionEvent e) {
        String did = e.getUser().getId();
        UUID id = uuidByDiscord(did);
        if (id == null) {
            e.reply("You don't have a linked Minecraft account.").setEphemeral(true).queue();
            return;
        }
        String name = plugin.players.nameFor(id);
        synchronized (this) {
            links.remove(id);
        }
        saveLater();
        netRemoveLink(id);
        logLink(name + " was unlinked from Discord account " + e.getUser().getName());
        removeRole(did);
        e.reply("Unlinked **" + name + "**.").setEphemeral(true).queue();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player on = Bukkit.getPlayer(id);
            if (on != null) plugin.msg.send(on, "unlink-done");
        });
    }

    private void discordProfile(SlashCommandInteractionEvent e) {
        var opt = e.getOption("user");
        var target = opt != null ? opt.getAsUser() : e.getUser();
        UUID id = uuidByDiscord(target.getId());
        if (id == null) {
            e.reply(target.getId().equals(e.getUser().getId()) ? "You are not linked. Type /link in Minecraft."
                    : "That member is not linked.").setEphemeral(true).queue();
            return;
        }
        e.deferReply(true).queue();
        Bukkit.getScheduler().runTask(plugin, () -> {
            String name = plugin.players.nameFor(id);
            Player on = Bukkit.getPlayer(id);
            String limit = plugin.homes.cachedLimit(id) > 100000 ? "\u221e" : String.valueOf(plugin.homes.cachedLimit(id));
            EmbedBuilder eb = new EmbedBuilder().setTitle(name).setColor(0x55FFFF)
                    .addField("Money", plugin.economy.enabled()
                            ? plugin.economy.format(plugin.economy.get(id)) : "-", true)
                    .addField("Homes", plugin.homes.all(id).size() + "/" + limit, true)
                    .addField("Status", on != null
                            ? "Online (" + Dur.format(TpaManager.playMinutes(on) * 60_000L) + " played)" : "Offline", true);
            e.getHook().sendMessageEmbeds(eb.build()).queue();
        });
    }

    // ------------------------------------------------------------ wrong-code limit (per Discord user)
    private boolean blocked(String discordId) {
        long[] t = tries.get(discordId);
        if (t == null) return false;
        if (System.currentTimeMillis() - t[1] > 600_000L) {
            tries.remove(discordId);
            return false;
        }
        return t[0] >= 5;
    }

    private void fail(String discordId) {
        tries.compute(discordId, (k, v) -> v == null ? new long[]{1, System.currentTimeMillis()}
                : new long[]{v[0] + 1, v[1]});
    }

    // ------------------------------------------------------------ Minecraft: /link makes a code
    public void link(Player p) {
        Msg m = plugin.msg;
        boolean viaNet = plugin.net != null && plugin.getConfig().getBoolean("discord.network-link", true);
        if (!enabled() && !viaNet) {
            m.send(p, "discord-disabled");
            return;
        }
        if (enabled() && !ready()) {
            m.send(p, "link-failed");
            return;
        }
        UUID id = p.getUniqueId();
        if (get(id) != null) {
            m.send(p, "link-already");
            return;
        }
        long now = System.currentTimeMillis();
        long minutes = Math.max(1, plugin.getConfig().getInt("discord.code-minutes", 10));
        pending.values().removeIf(x -> x.uuid().equals(id) || x.expires() < now);
        String code;
        do {
            code = randomCode();
        } while (pending.containsKey(code));
        pending.put(code, new Pending(id, p.getName(), now + minutes * 60_000L));
        if (plugin.net != null) {
            com.google.gson.JsonObject co = new com.google.gson.JsonObject();
            co.addProperty("u", id.toString());
            co.addProperty("n", p.getName());
            co.addProperty("x", now + minutes * 60_000L);
            plugin.net.put("code", code, co.toString());
        }
        m.send(p, "link-code", "code", code, "minutes", String.valueOf(minutes));
    }

    // ------------------------------------------------------------ rewards (money + console commands, once per account)
    private void reward(UUID id, String discordId, Player on) {
        synchronized (this) {
            // one reward per Minecraft account AND per Discord account (stops unlink/relink alt farming)
            if (rewarded.contains(id) || rewardedDiscord.contains(discordId)) return;
            rewarded.add(id);
            rewardedDiscord.add(discordId);
        }
        if (plugin.net != null) {
            plugin.net.put("rew", id.toString(), "1");
            plugin.net.put("rewd", discordId, "1");
        }
        double amt = plugin.getConfig().getDouble("discord.link-reward", 0);
        if (amt > 0 && plugin.economy.enabled()) {
            plugin.economy.deposit(id, amt);
            plugin.economy.logMisc("rewards", "Discord link reward", id, amt, false);
            if (on != null) plugin.msg.send(on, "link-reward", "amount", plugin.economy.format(amt));
        }
        if (!plugin.getConfig().getStringList("discord.link-commands").isEmpty()) {
            if (on != null) {
                runCommands(on);
            } else {
                synchronized (this) {
                    pendingCmds.add(id);
                }
            }
        }
        saveLater();
    }

    private void runCommands(Player p) {
        for (String cmd : plugin.getConfig().getStringList("discord.link-commands")) {
            String c = cmd.replace("{player}", p.getName()).replace("{uuid}", p.getUniqueId().toString());
            if (c.startsWith("/")) c = c.substring(1);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c);
        }
    }

    /** Called when a player joins: gives reward commands that were waiting for them. */
    public void onJoin(Player p) {
        boolean had;
        synchronized (this) {
            had = pendingCmds.remove(p.getUniqueId());
        }
        if (had) {
            runCommands(p);
            saveLater();
        }
    }

    public void unlink(Player p) {
        Msg m = plugin.msg;
        Link l = get(p.getUniqueId());
        if (l == null) {
            m.send(p, "unlink-none");
            return;
        }
        synchronized (this) {
            links.remove(p.getUniqueId());
        }
        saveLater();
        netRemoveLink(p.getUniqueId());
        logLink(p.getName() + " unlinked their Discord account (" + l.discordName() + ")");
        removeRole(l.discordId());
        m.send(p, "unlink-done");
    }

    // ------------------------------------------------------------ network sharing
    private void netPutLink(UUID id, Link l) {
        if (plugin.net == null) return;
        plugin.net.put("link", id.toString(), linkJson(l));
    }

    private void netRemoveLink(UUID id) {
        if (plugin.net != null) plugin.net.remove("link", id.toString());
    }

    private static String linkJson(Link l) {
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("d", l.discordId());
        o.addProperty("n", l.discordName());
        o.addProperty("a", l.at());
        return o.toString();
    }

    /** A /link code that was made on another server. */
    private Pending netCode(String code) {
        if (plugin.net == null) return null;
        String v = plugin.net.get("code", code);
        if (v == null) return null;
        try {
            com.google.gson.JsonObject o = new com.google.gson.JsonParser().parse(v).getAsJsonObject();
            return new Pending(UUID.fromString(o.get("u").getAsString()), o.get("n").getAsString(), o.get("x").getAsLong());
        } catch (Exception e) {
            return null;
        }
    }

    public void applyRemote(String ns, String key, String value, boolean deleted) {
        if (ns.equals("rewd")) {
            synchronized (this) {
                if (deleted || value == null) rewardedDiscord.remove(key);
                else rewardedDiscord.add(key);
            }
            return;
        }
        UUID id;
        try {
            id = UUID.fromString(key);
        } catch (IllegalArgumentException e) {
            return;
        }
        synchronized (this) {
            if (ns.equals("rew")) {
                if (deleted || value == null) rewarded.remove(id);
                else rewarded.add(id);
                return;
            }
            if (deleted || value == null) {
                links.remove(id);
                return;
            }
            com.google.gson.JsonObject o = new com.google.gson.JsonParser().parse(value).getAsJsonObject();
            links.put(id, new Link(o.get("d").getAsString(), o.get("n").getAsString(), o.get("a").getAsLong()));
        }
    }

    public synchronized void exportLocal() {
        for (Map.Entry<UUID, Link> e : links.entrySet()) {
            plugin.net.putIfAbsent("link", e.getKey().toString(), linkJson(e.getValue()));
        }
        for (UUID id : rewarded) plugin.net.putIfAbsent("rew", id.toString(), "1");
        for (String d : rewardedDiscord) plugin.net.putIfAbsent("rewd", d, "1");
    }

    // ------------------------------------------------------------ log channels
    /** discord.logs.<type>-channel-id, or the default discord.logs.channel-id. */
    private String channelId(String type) {
        var c = plugin.getConfig();
        String id = c.getString("discord.logs." + type + "-channel-id", "").trim();
        if (id.isEmpty()) id = c.getString("discord.logs.channel-id", "").trim();
        return id;
    }

    private static String safe(String s) {
        if (s == null || s.isBlank()) return "-";
        return s.length() > 900 ? s.substring(0, 900) + "..." : s;
    }

    private boolean sendEmbed(String type, EmbedBuilder eb) {
        JDA j = jda;
        String id = channelId(type);
        if (j == null || id.isEmpty()) return false;
        TextChannel ch = j.getTextChannelById(id);
        if (ch == null) {
            plugin.getLogger().warning("Discord log: channel " + id
                    + " not found (is the bot in that server, and can it see the channel?)");
            return false;
        }
        ch.sendMessageEmbeds(eb.build()).queue(null,
                err -> plugin.getLogger().warning("Discord log failed: " + err.getMessage()));
        return true;
    }

    /** Ban / timeout / kick (lifted = false) or unban / timeout removed (lifted = true). */
    public void logPunishment(HistoryManager.Entry e, boolean lifted) {
        if (!ready()) return;
        String title;
        int color;
        if (lifted) {
            title = e.type == PunishManager.Type.BAN ? "Unban" : "Timeout removed";
            color = 0x55FF55;
        } else {
            switch (e.type) {
                case BAN -> {
                    title = "Ban";
                    color = 0xFF5555;
                }
                case TIMEOUT -> {
                    title = "Timeout";
                    color = 0xFFAA00;
                }
                default -> {
                    title = "Kick";
                    color = 0xFFFF55;
                }
            }
        }
        EmbedBuilder eb = new EmbedBuilder().setTitle(title + " | " + e.id).setColor(color)
                .addField("Player", safe(e.name), true)
                .addField(lifted ? "Removed by" : "Staff", safe(lifted ? e.liftedBy : e.staff), true);
        if (!lifted) {
            eb.addField("Reason", safe(e.reason), false);
            if (e.type != PunishManager.Type.KICK) {
                eb.addField("Duration", e.until == 0 ? "Permanent"
                        : "Until <t:" + (e.until / 1000) + ":f> (<t:" + (e.until / 1000) + ":R>)", false);
            }
        } else {
            eb.addField("Original reason", safe(e.reason), false);
        }
        eb.setFooter(plugin.serverName()).setTimestamp(Instant.now());
        sendEmbed("punishments", eb);
    }

    public void logEconomy(String text) {
        logEconomy("Economy", text, 0xFFD700);
    }

    public void logEconomy(String title, String text, int color) {
        if (!ready()) return;
        sendEmbed("economy", new EmbedBuilder().setTitle(title).setDescription(safe(text))
                .setColor(color).setFooter(plugin.serverName()).setTimestamp(Instant.now()));
    }

    public void logLink(String text) {
        if (!ready()) return;
        sendEmbed("links", new EmbedBuilder().setTitle("Account link").setDescription(safe(text))
                .setColor(0x55FFFF).setFooter(plugin.serverName()).setTimestamp(Instant.now()));
    }

    /** /smpcore testlog: posts a test message to every configured log channel. Returns how many channels got one. */
    public int testLog() {
        if (!ready()) return 0;
        java.util.Set<String> seen = new java.util.HashSet<>();
        int n = 0;
        for (String t : new String[]{"punishments", "economy", "links", "commands"}) {
            String id = channelId(t);
            if (id.isEmpty() || !seen.add(id)) continue;
            if (sendEmbed(t, new EmbedBuilder().setTitle("Test log")
                    .setDescription("The DiamondCraft log channel works.").setColor(0x55FFFF))) n++;
        }
        return n;
    }

    public void logCommand(String who, String line, boolean ok, String result) {
        if (!ready() || !plugin.getConfig().getBoolean("command-log.discord", true)) return;
        sendEmbed("commands", new EmbedBuilder().setTitle(ok ? "Command OK" : "Command FAILED")
                .setColor(ok ? 0x55FF55 : 0xFF5555)
                .addField("Who", safe(who), true).addField("Command", safe(line), false)
                .addField("Result", safe(result), false)
                .setFooter(plugin.serverName()).setTimestamp(Instant.now()));
    }
}
