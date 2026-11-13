# Database Migrations
Apply in exactly this order (plain `ls | sort` gives it):
001_init, 002_seed_example_products, 003_clan_voting, 004_seed_clan_reward_tiers, 005_seed_backpack_products, 006_product_rarity, 007_bundles_cosmetics, 008_catalogue_schema, 008_player_accounts, 009_catalogue, 009_claim_tokens, 010_seed_item_catalog, 011_fairness_cash_items, 012_delivery_claim_lease.
All of them applied cleanly on a fresh PostgreSQL 16, in this order. Seeds use ON CONFLICT DO NOTHING; 012 uses IF NOT EXISTS. Back up production first (`pg_dump`). Windows: `psql "%DATABASE_URL%" -v ON_ERROR_STOP=1 -f migrations\NNN_name.sql` for each file.
Manual: seed 010 slug `food-banku-tilapia` collides with 009 and is skipped. Create the first admin user manually.
