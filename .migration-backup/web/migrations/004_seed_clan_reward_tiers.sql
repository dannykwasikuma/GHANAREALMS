-- Default clan vote reward tiers. These are placeholder in-game rewards,
-- not finalized values — same "seed data to verify the pipeline works,
-- not the real numbers" posture as 002_seed_example_products.sql. Adjust
-- give_money_minor / delivery_commands / rank_permission to whatever
-- GhanaRealms actually wants to grant before this goes live.
--
-- Bands: ranks 1-3, 4-10, 11-20 for both WEEKLY and MONTHLY. A period's
-- reward_tier_count defaults to 10 (set in migrations/003), so the 11-20
-- band only pays out once an admin raises reward_tier_count to 20 for a
-- given period — these rows exist either way so that's a one-field change,
-- not a new migration.

-- Every member of a rewarded clan receives these, so money is per player.
-- Gear is plain vanilla `give` (works on any version); swap for custom-item
-- commands once the custom weapons/gear exist. Higher rank band = higher gear tier.
INSERT INTO clan_reward_tiers (period_type, min_rank, max_rank, label, give_money_minor, delivery_commands) VALUES
  ('WEEKLY', 1, 3, 'Top 3', 1000, ARRAY['give {player} diamond_sword 1','give {player} diamond_chestplate 1']),
  ('WEEKLY', 4, 10, 'Rank 4-10', 500, ARRAY['give {player} iron_sword 1','give {player} iron_chestplate 1']),
  ('WEEKLY', 11, 20, 'Rank 11-20', 250, ARRAY['give {player} stone_sword 1','give {player} chainmail_chestplate 1']),
  ('MONTHLY', 1, 3, 'Top 3', 4000, ARRAY['give {player} netherite_sword 1','give {player} netherite_chestplate 1']),
  ('MONTHLY', 4, 10, 'Rank 4-10', 2000, ARRAY['give {player} diamond_sword 1','give {player} diamond_chestplate 1']),
  ('MONTHLY', 11, 20, 'Rank 11-20', 1000, ARRAY['give {player} iron_sword 1','give {player} iron_chestplate 1'])
ON CONFLICT (period_type, min_rank, max_rank) DO NOTHING;
