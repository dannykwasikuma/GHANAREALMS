-- Fairness pass over 010_seed_item_catalog.sql (run after it).
--  * Powerful / progression-shortcut items from 010 stop being sold for real money: resource stacks,
--    the PvP and Nether kits, rare+ tool sets, utility items (pearls, totem, rockets, XP, shulker, enchanter
--    setup), golden apples. They become in-game-coin items with per-player limits.
--  * Beacon is gameplay-only (not for sale), matching 009's Beacon.
--  * Cheap convenience items stay GH₵ (miner/farmer/builder/explorer/fisher kits, basic + iron tool sets,
--    Ghanaian food packs, Ender Chest), with a 1-per-7-days kit limit.
--  * Every 010 item also gets item_specs/icon so /gshop and the catalogue page can show and sell it.
-- Coin prices follow the 009 scale (iron ingot 25, gold 60, diamond 400, netherite ingot 5000). No
-- exchange rate between coins and GH₵ is assumed. Edit anything afterwards in /admin/products.
BEGIN;

-- 1) item_specs from the give-commands (only when every command is a plain `give {player} <item> <n>`)
UPDATE products p SET
  item_specs = ARRAY(SELECT upper(regexp_replace(c, '^give \{player\} (\S+) (\d+)$', '\1 \2')) FROM unnest(p.delivery_commands) AS c),
  icon_material = upper(split_part(substring(p.delivery_commands[1] from '^give \{player\} (\S+)'), ' ', 1))
WHERE cardinality(p.item_specs) = 0 AND cardinality(p.delivery_commands) > 0
  AND p.category IN ('kit','tool','resource','food','utility')
  AND NOT EXISTS (SELECT 1 FROM unnest(p.delivery_commands) AS c WHERE c !~ '^give \{player\} \S+ \d+$');

-- 2) coin-only items: (slug, coins, limit per player, limit window days)
UPDATE products p SET price_pesewas = NULL, ingame_price_minor = v.coins,
       purchase_limit_per_player = v.lim, limit_window_days = v.win, updated_at = now()
FROM (VALUES
  ('resource-coal', 120, NULL::int, NULL::int), ('resource-copper', 100, NULL, NULL), ('resource-iron', 1600, NULL, NULL),
  ('resource-gold', 3840, NULL, NULL), ('resource-redstone', 300, NULL, NULL), ('resource-lapis', 400, NULL, NULL),
  ('resource-diamond', 6400, 2, 7), ('resource-emerald', 4800, 2, 7), ('resource-obsidian', 960, NULL, NULL),
  ('resource-netherite', 5000, 1, 7), ('resource-quartz', 640, NULL, NULL), ('resource-oak-log', 200, NULL, NULL),
  ('kit-pvp', 2200, 1, 3), ('kit-nether', 1400, 2, 7),
  ('tool-rare', 5500, 1, 7), ('tool-epic', 30000, 1, 7), ('tool-legendary', 36000, 1, 14),
  ('food-golden-apples', 1200, 2, 7),
  ('utility-ender-pearls', 1100, 2, 1), ('utility-totem', 4000, 2, 7), ('utility-fireworks', 600, NULL, NULL),
  ('utility-xp-bottles', 1500, 2, 1), ('utility-shulker-box', 3000, 3, 7), ('utility-enchanter', 2500, 1, 7)
) AS v(slug, coins, lim, win)
WHERE p.slug = v.slug;

-- 3) Beacon: gameplay only
UPDATE products SET price_pesewas = NULL, gameplay_only = true, updated_at = now() WHERE slug = 'utility-beacon';

-- 4) cash kits: 1 per 7 days (convenience, not a stockpile route)
UPDATE products SET purchase_limit_per_player = 1, limit_window_days = 7, updated_at = now()
WHERE slug IN ('kit-miner','kit-farmer','kit-builder','kit-explorer','kit-fisher') AND purchase_limit_per_player IS NULL;

COMMIT;
