# Migration order (apply with psql, in this exact order)

001_init, 002_seed_example_products, 003_clan_voting, 004_seed_clan_reward_tiers,
005_seed_backpack_products, 006_product_rarity, 007_bundles_cosmetics,
008_catalogue_schema, 008_player_accounts, 009_catalogue, 009_claim_tokens,
010_seed_item_catalog, 011_fairness_cash_items

Two files share each of the numbers 008 and 009 (catalogue work and player-account work were
written separately). They do not depend on each other, but run them in the order above; plain
`ls | sort` gives exactly this order. Verified: the whole sequence applies cleanly to a fresh
PostgreSQL 16 database, and the seed files (004, 009, 010) are safe to re-run.

011 moves progression-shortcut items from 010 (resource stacks, PvP/Nether kits, rare+ tool sets, utilities) from GH₵ to in-game coins with limits, and fills item_specs so /gshop can sell them. Verified on PostgreSQL 16.
