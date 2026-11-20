# Network setup (Velocity + several servers)

What gets shared between all your servers (through one MySQL/MariaDB database):
money, homes (with the server they are on), bans/timeouts/kicks + history + IDs, favorites, settings,
Discord links, player names, and /tpa /tpahere between servers.

## 1. Create the database
Panel -> your server -> Databases -> Create database. Note host, port, database name, user, password.
All servers must be able to reach this database.

## 2. Velocity
- velocity.toml: every backend server needs a name in [servers]. Remember these names EXACTLY.
- bungee-plugin-message-channel = true  (this is the default). It is needed to send players between servers.
- Use the same plugin jar (DiamondCraft) on every backend server.

## 3. Config on EVERY backend server (plugins/DiamondCraft/config.yml)
network:
  enabled: true
  server-name: "<this server's name in velocity.toml>"   # different on every server!
  database: host / port / name / user / password (the same on all servers)

## 4. First start
Start your MAIN server (the one that has your real data) first. It copies its local data
(homes, balances, bans, history, favorites, settings, links) into the database once
(file network-imported.flag is created). Then start the other servers.
Other servers also import their own local data once; players that already exist in the database keep the database version.
If you do not want an old server's data imported, delete its homes.yml, balances.yml, etc. before the first start.

## 5. Good to know
- Changes show up on other servers within about 3 seconds (network.sync-seconds).
- Home limits use LuckPerms: share LuckPerms between servers too (same database), otherwise ranks differ per server.
- A home on another server works if the world name exists on that server.
- /tpa and /tpahere work with players on other servers (they are listed with the server name). The player is moved
  with Velocity and teleported when they arrive.
- Run the Discord bot (discord.enabled) on ONE server only. With network-link: true, /link works on every server.
- If the database is down at startup the plugin falls back to local files and says so in the console. Do not keep running like that.

## Cracked (offline-mode) servers
- Velocity: online-mode = false. Every backend server: online-mode=false and forwarding set up (modern forwarding + the same forwarding secret, or legacy).
- Firewall every backend server so players can only connect through Velocity, never directly.
- IMPORTANT: on a cracked server anyone can join with any name. Install an AUTH plugin (AuthMe, nLogin, ...) so nobody can log in as your admins.
  OPs, staff permissions, money, homes and bans are tied to the NAME, so without auth they are not safe.
- "Steve" and "sTeve" are different accounts on cracked servers (different UUIDs). Make your auth plugin enforce the exact name spelling.
- Set economy.starting-balance low (0 is safest): new names are new accounts.
