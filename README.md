# DiamondCraft

Homes + TPA (pause-menu style button screens), custom ban/kick/timeout screens, LuckPerms home limits.
Everything is in plugins/SMPCore/config.yml.

## Build
Needs Java 21+ and Maven:   mvn package
Jar: target/SMPCore.jar -> drop in plugins/

## Commands
/sethome [name] /home [name] /homes /delhome <name>
/tpa [player] /tpahere [player] /tpaccept /tpdeny /tpacancel
/ban <player> [duration] [reason]   /unban
/kick <player> [reason]
/timeout <player> <duration> [reason]   /untimeout
/smpcore reload

## Home limits (LuckPerms)
config.yml -> homes.group-limits  (group name = LuckPerms group)
or give permission smpcore.homes.<number> / smpcore.homes.unlimited

## Geyser / Floodgate
- Install Floodgate: Bedrock players automatically get native Bedrock forms, Java players get the dialog menus.
- Offline ban/timeout: works for any name; Bedrock players use their prefix (e.g. /ban .Steve 7d griefing).
- Names that ever tried to join are remembered in players.yml.

## Esc menu
Java 1.21.6+: a "DiamondCraft" button appears in the Esc menu (config: esc-menu). Restart the server after changing it.
Bedrock has no pause-menu buttons; Bedrock players use the commands (/homes, /tpa ...).
The plugin is now a Paper plugin (paper-plugin.yml). Commands are registered in code.

## Economy, Shop, Settings
- economy.enabled in config.yml turns the economy on/off. Needs Vault so EconomyShopGUI uses it.
- /balance /pay /baltop /eco give|take|set, /settings (night vision, TPA and TPA Here switches), /menu
- Shop and Sell buttons run EconomyShopGUI's /shop and /sellgui. Auction button runs another plugin's command (default: ah).
- tpa.* in config: enable/disable /tpa and /tpahere and set a minimum playtime.
- homes.default-limit + homes.group-limits (LuckPerms group names) for free and VIP homes.

## Status, Teleport hub, Favorites
- /status shows name, money, playtime and homes. /tpmenu opens TPA, TPA Here and Favorites. /tpfav [add|remove <player>] manages favourite friends (they appear first in the TPA lists).

## PlaceholderAPI (scoreboard / TAB / holograms)
%diamondcraft_balance% _balance_short% _balance_raw% | _homes% _homelimit% _homes_full% _homes_left%
_playtime% _playtime_minutes% _playtime_hours% | _favorites% _favorites_online% | _nightvision% _tpa_requests%
%diamondcraft_baltop_1_name% %diamondcraft_baltop_1_balance% (1 to 10)

## RTP / RTP Queue
- rtp.enabled: false closes /rtp and /rtpq. rtp.queue.enabled: false closes only /rtpq.
- rtp.worlds.<overworld|nether|end>.enabled: false closes RTP to that world. Distances, price and world names are editable.

## Updating the jar
Replace only the jar; keep plugins/DiamondCraft/. On start, missing settings are added to your config.yml (your values are never changed) and a backup config-backup-<time>.yml is saved first.
Lists you edited (e.g. esc-menu.buttons) are left as they are, so add new buttons by hand.

## Staff punishment menu
/punish (alias /staff) opens the staff menu (permission smpcore.staff, OP by default): punish a player, lists of active bans/timeouts, kicks, all history.
/punish id <ID> and /punish player <name> search. Every ban/timeout/kick needs a reason and gets an ID (B-12, T-5, K-30). All logged in history.yml.
Players with smpcore.punish.exempt cannot be punished by staff.

## Discord link
discord.* in config.yml (disabled by default). /link <code> and /unlink in game. Placeholders %diamondcraft_discord% and %diamondcraft_linked%.
Setup: docs/DISCORD-SETUP.md (the bot runs inside the plugin, built with JDA).

## Network mode (Velocity)
network.* in config.yml, setup in docs/NETWORK-SETUP.md. Uses MySQL/MariaDB (HikariCP + MariaDB driver are bundled).

## Economy log (Discord)
Put your channel ID in discord.logs.economy-channel-id (or the default channel-id), then /smpcore reload and /smpcore testlog.
Logged: /eco give/take/set (always), every /pay, shop buys and sells through Vault (EconomyShopGUI, with the plugin name; fast sell-all moves are grouped into one line), RTP prices/refunds, link rewards.
Switches: economy.log.* in config.yml. Also written to economy-log.txt.

## Combat timer
combat.* in config.yml. A fight (player vs player, or mobs too with pvp-only: false) blocks DiamondCraft commands, menu buttons and teleports for combat.seconds (default 20).
combat.allowed-commands (staff tools) still work. combat.extra-blocked-commands can block other plugins' commands (shop, spawn...). Permission smpcore.combat.bypass ignores combat.
Placeholders: %diamondcraft_combat% (Yes/No), %diamondcraft_combat_seconds%.

## Homes: names and privacy
"Set new home here" in the Homes menu asks for a name (Java dialog, Bedrock form, or chat on old versions). Each home has a Rename button. Coordinates are never shown.

## Settings: TPA switches
Settings has two separate buttons: accept TPA requests and accept TPA Here requests (a button is hidden when that command is disabled in tpa.*). Placeholders: %diamondcraft_tpa_requests% and %diamondcraft_tpahere_requests%.

## Paying offline players
/pay <name> <amount> works for anyone who ever joined (even before the plugin was installed). The Pay menu has "All players (also offline)" (paged) and "Search a player by name".

## Server restart
/serverrestart <minutes> (alias /restartin) announces the restart right away and then every 5 minutes (30, 25, 20 ... 5), counts down the last 5 seconds, kicks everyone and restarts. /serverrestart cancel stops it, /serverrestart shows the time left. Permission smpcore.restart (OP, console works too). Settings: restart.* in config.yml. restart.method: restart uses Spigot's restart script (spigot.yml restart-script), stop just stops the server so your host/panel must start it again. The name is not /restart because Spigot already owns that command.

## Bounties
/bounty (or /bounties) opens the bounty menu; /bounty <player> <amount> puts money from your balance on someone's head (works for offline players too, amounts like 25k and 1m are fine); /bounty <player> shows their bounty. Whoever kills that player in PvP collects the whole bounty. Bounties stack, are saved across restarts and shared across servers in network mode.
Settings: bounty.* in config.yml (min-amount, announce, presets). There is a Bounty button in /menu and the Esc menu. Placed and collected bounties go to the economy log (economy.log.bounty). Placeholders: %diamondcraft_bounty% and %diamondcraft_bounty_raw%. Permission smpcore.bounty (everyone).

## Mute
/mute <player> [duration] <reason> and /unmute <player> (permission smpcore.mute, OP by default). Works like /timeout: reason required, no duration = permanent, saved in the punishment history (ID starts with M-), shown in the /punish staff menu and logged to the Discord punishments channel.
A muted player can't send chat, can't use the commands in mute.blocked-commands (msg, tell, w, r, me, say...), and with mute.hide-chat: true can't see other players' chat. Join/leave/death messages and staff broadcasts are not hidden.

## Security notes
- staff.protected (config): names/UUIDs staff can never punish. OPs are protected from non-OP staff. smpcore.punish.exempt also protects.
- smpcore.punish.override: allows replacing a longer ban/timeout with a shorter one (OP by default).
- Admin /eco actions and payments over economy.log-pay-over are written to economy-log.txt.
- In network mode, taking money is one atomic database step (no double spending between servers).
