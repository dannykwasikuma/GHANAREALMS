# Clan voting + backpack (untested - nothing was run in the build sandbox)

## Clan voting
- Plugin `GhanaRealmsClans`: /clan create|invite|accept|leave|disband|info, /clanvote <tag>. Clans live in local SQLite and are full-roster synced to `/api/bridge/clans/sync` (on change + every SYNC-INTERVAL-SECONDS).
- Votes (weekly AND monthly per /clanvote) go to `/api/bridge/clans/vote`; one vote per player per period (DB unique), no voting for your own clan, plugin-only, closes at period end.
- Settlement: admin button at /admin/clans, only after period end. Rank = votes desc, then clan id asc. Rewards come from fixed bands in `clan_reward_tiers` (seeded 1-3, 4-10, 11-20; top N per period via `reward_tier_count`, 10 default, max 20). Gifts (in-game currency, plus rank/commands if you fill those columns) go to EVERY member who was active when voting closed (joined on/before period_end and hadn't left), through the existing delivery_queue; seeded gifts are money plus gear scaled by rank band (stone/iron -> diamond -> netherite). Every settlement is audit-logged.
- Edit the placeholder gift values in migrations/004 (or the table) before going live.

## Backpack
- Plugin `GhanaRealmsBackpack`: /backpack (alias /bp) opens a persistent chest of 1-6 rows (DEFAULT-ROWS in config). `/backpack setrows <player> <n>` is upgrade-only.
- Store: migration 005 adds 3 / 4 / 6-row products. They appear on /store automatically (products table) and deliver via `backpack setrows {player} N` run by the Paystack plugin as console.
- Caveat: delivery depends on the Paystack plugin running `delivery_commands` on console - confirm in its delivery code.

## Weapons & gear tiers
Migration 006 adds `products.rarity` (BASIC, UNCOMMON, RARE, EPIC, LEGENDARY) and seeds a weapon and a gear product per tier. /store groups by category and orders Basic -> Legendary. Items use plain vanilla `give`; swap in custom-item commands later.

## Ghana flavour pass
- Store tiers display as Fugu / Kente / Adinkra / Asante Gold / Golden Stool (web/lib/rarity.ts; DB keeps BASIC..LEGENDARY).
- Migration 007: Akwaaba Starter + Asante Warrior bundles and five Adinkra title products. Store page has a Deal of the Week banner (first `featured` product), bundle section first, and the home page shows masked recent purchases.
- Plugin `GhanaRealmsAdinkra`: chat titles (`adinkra grant {player} <id>` from the store), Pidgin/Twi join messages, Twi command aliases (sika, fie, akwaaba - edit targets to match your plugins).
- MoMo: Paystack already offers mobile money for GHS; set `PAYSTACK_CHANNELS=mobile_money,card` to restrict/order channels.
- Not built: crates, Ghana warps/events, clan winner chat tag, themed worlds (need builds/other plugins).

## Loot chests
Plugin `GhanaRealmsLoot`: every SPAWN-INTERVAL-MINUTES a random chest appears at the surface in the configured survival world(s), between MIN/MAX distance from spawn, rarity rolled Fugu/Kente/Adinkra/Asante Gold/Golden Stool by weight. Coloured particle column marks it when a player is within 48 blocks; the announcement gives rounded coordinates. Unopened chests vanish after DESPAWN-MINUTES, empty ones are removed, chests can't be broken/hoppered/exploded. Admin: /lootchest spawn [RARITY]|list|clear|reload. Loot tables are all in config.yml. Not integrated with WorldGuard/claim plugins - add your claim-protected regions to avoid placing in someone's base.

## Raids & Mob Apocalypse
Plugin `GhanaRealmsRaids`. Raids: random eligible survival player (outside the spawn buffer) gets a 3-wave mob raid with a red boss bar; surviving rewards the target plus everyone within 40 blocks. Fails if the target dies/logs off or time runs out (10 min). Apocalypse: rare server-wide event (min 3h apart, 10 min long, forced night+storm) that keeps spawning mobs around every eligible player up to a cap; kills are counted, top 3 + anyone with 10+ kills get rewards; leftover mobs are removed. Event mobs never persist, creepers don't break blocks, everything (chances, mob lists, rewards, caps) is in config.yml. Admin: /raid start [player]|stop|status, /apocalypse start|stop|status. Reward COMMANDS are empty by default - add your economy command (e.g. `eco give {player} 500`).
