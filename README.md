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
- /balance /pay /baltop /eco give|take|set, /settings (night vision, accept teleport requests), /menu
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
