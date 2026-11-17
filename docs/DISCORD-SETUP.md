# Discord link setup (5 minutes)

The bot runs inside the plugin. Use a NEW bot for this (do not reuse your other bot's token).

1. Go to https://discord.com/developers/applications -> New Application -> name it.
2. Bot tab -> Reset Token -> copy the token. (No privileged intents are needed: leave them OFF.)
3. Installation / OAuth2 -> URL Generator: scopes `bot` and `applications.commands`.
   Bot permissions: only "Manage Roles" if you want a linked role (optional). Open the generated link and add the bot to your server.
4. In Discord: Settings -> Advanced -> Developer Mode ON. Right-click your server icon -> Copy Server ID.
5. In plugins/DiamondCraft/config.yml:
   discord:
     enabled: true
     bot-token: "<your token>"
     guild-id: "<server id>"
     linked-role-id: "<optional role id>"
6. Restart the server. The console shows "Discord bot connected as ...".

Players: /link in Minecraft -> code -> /link CODE in Discord. /unlink works on both sides. /profile (Discord) shows a linked player.
Rewards: discord.link-reward (money) and discord.link-commands (console commands, once per account).
If you want a linked role: put the bot's role ABOVE the linked role in Server Settings -> Roles.
The token is a password: never post it, never commit config.yml to GitHub.
