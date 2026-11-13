-- Item catalogue seed: kits, tool sets, resource stacks, Ghanaian-style food packs and utility items.
--
-- Run:  psql "$DATABASE_URL" -f web/migrations/010_seed_item_catalog.sql
--
-- Safe to re-run: every row is keyed on `slug` and uses ON CONFLICT DO NOTHING, so it never
-- overwrites a price you changed later. To change a price afterwards, UPDATE products SET
-- price_pesewas = ... WHERE slug = '...';  (100 pesewas = GH₵1).
--
-- Items are delivered through the normal delivery_queue -> GhanaRealmsPaystack path as vanilla
-- `give {player} <item> <count>` console commands (version-proof; no enchant/NBT syntax).
-- Prices are PLACEHOLDERS - set real ones before launch. Requires 006 (rarity column).
-- The storefront groups by category; page.tsx knows: kit, tool, resource, food, utility.

BEGIN;

INSERT INTO products (name, slug, description, price_pesewas, category, rarity, delivery_commands, sort_order) VALUES
 ('Miner''s Kit','kit-miner','Iron pickaxe and shovel, 64 torches, 32 steak and a bucket - dig in and stay fed.',1200,'kit',NULL,ARRAY['give {player} iron_pickaxe 1','give {player} iron_shovel 1','give {player} torch 64','give {player} cooked_beef 32','give {player} bucket 1'],50),
 ('Farmer''s Kit','kit-farmer','Iron hoe, seeds, crops, bone meal and a water bucket to start a farm fast.',1000,'kit',NULL,ARRAY['give {player} iron_hoe 1','give {player} wheat_seeds 64','give {player} carrot 32','give {player} potato 32','give {player} bone_meal 32','give {player} water_bucket 1'],51),
 ('Builder''s Kit','kit-builder','Stone bricks, planks, glass and ladders for a quick base.',1500,'kit',NULL,ARRAY['give {player} stone_bricks 64','give {player} oak_planks 64','give {player} glass 64','give {player} ladder 32','give {player} iron_axe 1'],52),
 ('Explorer''s Kit','kit-explorer','Compass, map, boat, torches, bread and ender pearls for the long road.',1800,'kit',NULL,ARRAY['give {player} compass 1','give {player} map 1','give {player} oak_boat 1','give {player} torch 32','give {player} bread 32','give {player} ender_pearl 4'],53),
 ('Fisher''s Kit','kit-fisher','Fishing rod and 32 cooked cod - Lake Volta vibes.',800,'kit',NULL,ARRAY['give {player} fishing_rod 1','give {player} cooked_cod 32'],54),
 ('Nether Expedition Kit','kit-nether','Gold boots, flint and steel, obsidian for a portal, and food.',2500,'kit',NULL,ARRAY['give {player} golden_boots 1','give {player} flint_and_steel 1','give {player} obsidian 14','give {player} cooked_porkchop 32','give {player} golden_apple 2'],55),
 ('PvP Starter Kit','kit-pvp','Iron armor, iron sword, shield, golden apples and steak.',3500,'kit',NULL,ARRAY['give {player} iron_helmet 1','give {player} iron_chestplate 1','give {player} iron_leggings 1','give {player} iron_boots 1','give {player} iron_sword 1','give {player} shield 1','give {player} golden_apple 4','give {player} cooked_beef 32'],56),
 ('Basic Tool Set','tool-basic','Stone pickaxe, axe, shovel and hoe.',400,'tool','BASIC',ARRAY['give {player} stone_pickaxe 1','give {player} stone_axe 1','give {player} stone_shovel 1','give {player} stone_hoe 1'],60),
 ('Uncommon Tool Set','tool-uncommon','Iron pickaxe, axe, shovel and hoe.',1200,'tool','UNCOMMON',ARRAY['give {player} iron_pickaxe 1','give {player} iron_axe 1','give {player} iron_shovel 1','give {player} iron_hoe 1'],61),
 ('Rare Tool Set','tool-rare','Diamond pickaxe, axe, shovel and hoe.',3000,'tool','RARE',ARRAY['give {player} diamond_pickaxe 1','give {player} diamond_axe 1','give {player} diamond_shovel 1','give {player} diamond_hoe 1'],62),
 ('Epic Tool Set','tool-epic','Netherite pickaxe, axe, shovel and hoe.',6500,'tool','EPIC',ARRAY['give {player} netherite_pickaxe 1','give {player} netherite_axe 1','give {player} netherite_shovel 1','give {player} netherite_hoe 1'],63),
 ('Legendary Tool Set','tool-legendary','Netherite tool set plus 64 XP bottles and 8 golden apples.',12000,'tool','LEGENDARY',ARRAY['give {player} netherite_pickaxe 1','give {player} netherite_axe 1','give {player} netherite_shovel 1','give {player} netherite_hoe 1','give {player} experience_bottle 64','give {player} golden_apple 8'],64),
 ('Coal Stack','resource-coal','64 coal.',600,'resource',NULL,ARRAY['give {player} coal 64'],70),
 ('Copper Stack','resource-copper','64 copper ingots.',500,'resource',NULL,ARRAY['give {player} copper_ingot 64'],71),
 ('Iron Stack','resource-iron','64 iron ingots.',1500,'resource',NULL,ARRAY['give {player} iron_ingot 64'],72),
 ('Gold Stack','resource-gold','64 gold ingots.',2500,'resource',NULL,ARRAY['give {player} gold_ingot 64'],73),
 ('Redstone Stack','resource-redstone','64 redstone dust.',600,'resource',NULL,ARRAY['give {player} redstone 64'],74),
 ('Lapis Stack','resource-lapis','64 lapis lazuli.',800,'resource',NULL,ARRAY['give {player} lapis_lazuli 64'],75),
 ('Diamond Pack','resource-diamond','16 diamonds.',4000,'resource',NULL,ARRAY['give {player} diamond 16'],76),
 ('Emerald Pack','resource-emerald','32 emeralds.',2500,'resource',NULL,ARRAY['give {player} emerald 32'],77),
 ('Obsidian Pack','resource-obsidian','32 obsidian.',1500,'resource',NULL,ARRAY['give {player} obsidian 32'],78),
 ('Netherite Ingot','resource-netherite','1 netherite ingot.',8000,'resource',NULL,ARRAY['give {player} netherite_ingot 1'],79),
 ('Quartz Block Stack','resource-quartz','64 quartz blocks for clean builds.',1200,'resource',NULL,ARRAY['give {player} quartz_block 64'],80),
 ('Oak Log Stack','resource-oak-log','64 oak logs.',400,'resource',NULL,ARRAY['give {player} oak_log 64'],81),
 ('Jollof Feast','food-jollof','16 roast chicken, 16 baked potatoes, 8 golden carrots.',600,'food',NULL,ARRAY['give {player} cooked_chicken 16','give {player} baked_potato 16','give {player} golden_carrot 8'],80),
 ('Waakye Pack','food-waakye','16 steak and 16 bread.',500,'food',NULL,ARRAY['give {player} cooked_beef 16','give {player} bread 16'],81),
 ('Banku & Tilapia','food-banku-tilapia','16 cooked salmon, 16 cooked cod, 8 bread.',500,'food',NULL,ARRAY['give {player} cooked_salmon 16','give {player} cooked_cod 16','give {player} bread 8'],82),
 ('Kenkey & Fish','food-kenkey-fish','32 cooked cod and 16 bread.',450,'food',NULL,ARRAY['give {player} cooked_cod 32','give {player} bread 16'],83),
 ('Fufu & Light Soup','food-fufu-soup','8 rabbit stew and 16 cooked mutton.',600,'food',NULL,ARRAY['give {player} rabbit_stew 8','give {player} cooked_mutton 16'],84),
 ('Red Red Pack','food-red-red','8 beetroot soup and 16 cooked porkchops.',500,'food',NULL,ARRAY['give {player} beetroot_soup 8','give {player} cooked_porkchop 16'],85),
 ('Kelewele Snack Pack','food-kelewele','8 honey bottles and 8 pumpkin pies.',400,'food',NULL,ARRAY['give {player} honey_bottle 8','give {player} pumpkin_pie 8'],86),
 ('Golden Apple Pack','food-golden-apples','8 golden apples.',5000,'food',NULL,ARRAY['give {player} golden_apple 8'],87),
 ('Ender Pearl Pack','utility-ender-pearls','16 ender pearls.',1500,'utility',NULL,ARRAY['give {player} ender_pearl 16'],90),
 ('Totem of Undying','utility-totem','1 totem of undying.',5000,'utility',NULL,ARRAY['give {player} totem_of_undying 1'],91),
 ('Firework Rockets','utility-fireworks','64 firework rockets - pair with an elytra.',800,'utility',NULL,ARRAY['give {player} firework_rocket 64'],92),
 ('XP Bottles','utility-xp-bottles','64 bottles o'' enchanting.',3000,'utility',NULL,ARRAY['give {player} experience_bottle 64'],93),
 ('Shulker Box','utility-shulker-box','1 shulker box.',1500,'utility',NULL,ARRAY['give {player} shulker_box 1'],94),
 ('Ender Chest','utility-ender-chest','1 ender chest.',1000,'utility',NULL,ARRAY['give {player} ender_chest 1'],95),
 ('Enchanter''s Setup','utility-enchanter','Enchanting table, 15 bookshelves and 64 lapis.',3500,'utility',NULL,ARRAY['give {player} enchanting_table 1','give {player} bookshelf 15','give {player} lapis_lazuli 64'],96),
 ('Beacon','utility-beacon','1 beacon.',9000,'utility',NULL,ARRAY['give {player} beacon 1'],97)
ON CONFLICT (slug) DO NOTHING;

COMMIT;
