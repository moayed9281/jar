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
