-- GhanaRealms Store — Clan Leaderboard + Voting + Gift Settlement
--
-- No clan system existed anywhere in this repo before this migration (web
-- or any of the 9 Minecraft plugins — confirmed by grep). This builds it
-- from scratch, reusing existing conventions: pesewas-style integer money
-- is not needed here (gifts are in-game, see clan_reward_tiers reusing the
-- exact reward primitives already on `products`), but the bridge-secret
-- pattern, delivery_queue pipeline, and admin/audit_logs tables are all
-- reused rather than duplicated. See docs/CLAN_VOTING.md for the full
-- design and the anti-bias rules this schema enforces.

BEGIN;

-- ============================================================
-- clans: created by the in-game plugin via the sync bridge (see
-- /api/bridge/clans/sync), or seeded manually before the plugin exists —
-- same bootstrapping story as `products` (no admin UI yet either; see
-- STORE_PRODUCTS.md's existing pattern).
-- ============================================================
CREATE TABLE clans (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tag         TEXT NOT NULL UNIQUE, -- short in-game tag, e.g. "GRX"
    name        TEXT NOT NULL,
    leader_minecraft_player_id UUID REFERENCES minecraft_players(id),
    active      BOOLEAN NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_clans_active ON clans (active);

-- One active membership per player at a time. `left_at` is set rather than
-- deleting the row, so historical membership (who was in a clan during a
-- given vote period) is never lost — same "never mutate history" principle
-- the orders schema already uses for price snapshots.
CREATE TABLE clan_members (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    clan_id     UUID NOT NULL REFERENCES clans(id) ON DELETE CASCADE,
    minecraft_player_id UUID NOT NULL REFERENCES minecraft_players(id),
    role        TEXT NOT NULL DEFAULT 'member' CHECK (role IN ('leader','member')),
    joined_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at     TIMESTAMPTZ
);
CREATE INDEX idx_clan_members_clan ON clan_members (clan_id) WHERE left_at IS NULL;
-- A player can only be an active member of one clan at a time.
CREATE UNIQUE INDEX idx_clan_members_active_player
  ON clan_members (minecraft_player_id) WHERE left_at IS NULL;

-- ============================================================
-- clan_vote_periods: one row per weekly or monthly voting window.
-- `reward_tier_count` is how many ranked clans actually receive a gift
-- this period (the "top 10 or 20" the brief asked for) — configurable per
-- period rather than hardcoded, so weekly and monthly can differ.
-- ============================================================
CREATE TABLE clan_vote_periods (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    period_type TEXT NOT NULL CHECK (period_type IN ('WEEKLY','MONTHLY')),
    period_start TIMESTAMPTZ NOT NULL,
    period_end   TIMESTAMPTZ NOT NULL,
    status      TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','SETTLED')),
    reward_tier_count INT NOT NULL DEFAULT 10 CHECK (reward_tier_count BETWEEN 1 AND 20),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at  TIMESTAMPTZ,
    CHECK (period_end > period_start)
);
-- Only one OPEN period per type at a time — prevents votes from ever being
-- ambiguous about which period they belong to.
CREATE UNIQUE INDEX idx_clan_vote_periods_one_open_per_type
  ON clan_vote_periods (period_type) WHERE status = 'OPEN';
CREATE INDEX idx_clan_vote_periods_type_status ON clan_vote_periods (period_type, status);

-- ============================================================
-- clan_votes: the actual ballots. UNIQUE(period_id, voter) is the real
-- one-vote-per-player-per-period guarantee — not just an app-layer check.
-- Votes are submitted by the Minecraft plugin via the bridge (never a web
-- form), because the plugin already knows the real authenticated player on
-- the server; this is itself an anti-bias measure (no browser-submitted
-- vote can exist, so there's no "vote as someone else" surface at all).
-- ============================================================
CREATE TABLE clan_votes (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    period_id   UUID NOT NULL REFERENCES clan_vote_periods(id) ON DELETE CASCADE,
    voter_minecraft_player_id UUID NOT NULL REFERENCES minecraft_players(id),
    clan_id     UUID NOT NULL REFERENCES clans(id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (period_id, voter_minecraft_player_id)
);
CREATE INDEX idx_clan_votes_period_clan ON clan_votes (period_id, clan_id);

-- ============================================================
-- clan_reward_tiers: fixed, admin-configured gift bands by rank position.
-- Reuses the EXACT same reward primitives `products` already has
-- (rank_permission / duration_days / delivery_commands / give_money_minor)
-- so settlement can push through the same delivery_queue + Minecraft
-- bridge pipeline that purchases already use — no second delivery system.
-- Settlement always applies these bands mechanically by rank; no row here
-- is ever chosen per-clan by an admin, which is the actual "no bias
-- gifting" guarantee (see settlement logic in
-- app/api/admin/clans/periods/[id]/settle/route.ts).
-- ============================================================
CREATE TABLE clan_reward_tiers (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    period_type TEXT NOT NULL CHECK (period_type IN ('WEEKLY','MONTHLY')),
    min_rank    INT NOT NULL CHECK (min_rank >= 1),
    max_rank    INT NOT NULL,
    label       TEXT NOT NULL, -- e.g. "Top 3", "Rank 4-10"
    rank_permission TEXT,
    duration_days   INT,
    delivery_commands TEXT[] NOT NULL DEFAULT '{}',
    give_money_minor BIGINT NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (max_rank >= min_rank),
    UNIQUE (period_type, min_rank, max_rank)
);
CREATE INDEX idx_clan_reward_tiers_type ON clan_reward_tiers (period_type, min_rank);

-- ============================================================
-- clan_vote_rewards: one row per (rewarded clan, member) in a settled
-- period. Every player who was an active member of the clan when voting
-- closed (joined <= period_end, not left before it) gets the gift.
-- ============================================================
CREATE TABLE clan_vote_rewards (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    period_id   UUID NOT NULL REFERENCES clan_vote_periods(id) ON DELETE CASCADE,
    clan_id     UUID NOT NULL REFERENCES clans(id),
    rank        INT NOT NULL CHECK (rank >= 1),
    tier_id     UUID NOT NULL REFERENCES clan_reward_tiers(id),
    recipient_minecraft_player_id UUID NOT NULL REFERENCES minecraft_players(id),
    vote_count  INT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (period_id, clan_id, recipient_minecraft_player_id)
);
CREATE INDEX idx_clan_vote_rewards_period ON clan_vote_rewards (period_id);

-- ============================================================
-- Extend delivery_queue to carry a clan gift instead of a purchase, so the
-- SAME bridge endpoints (/api/bridge/pending-deliveries,
-- /api/bridge/ack-delivery) deliver both — no second delivery pipeline.
-- ============================================================
ALTER TABLE delivery_queue
  ALTER COLUMN order_item_id DROP NOT NULL,
  ADD COLUMN clan_vote_reward_id UUID REFERENCES clan_vote_rewards(id);

ALTER TABLE delivery_queue
  ADD CONSTRAINT delivery_queue_single_source CHECK (
    (order_item_id IS NOT NULL AND clan_vote_reward_id IS NULL) OR
    (order_item_id IS NULL AND clan_vote_reward_id IS NOT NULL)
  );

-- The existing UNIQUE(order_item_id) guarded against double-delivering a
-- purchase; clan_vote_rewards' own UNIQUE(period_id, clan_id, recipient) plus this new
-- index does the equivalent for gifts — one delivery per reward, ever.
CREATE UNIQUE INDEX idx_delivery_queue_clan_reward
  ON delivery_queue (clan_vote_reward_id) WHERE clan_vote_reward_id IS NOT NULL;

COMMIT;
