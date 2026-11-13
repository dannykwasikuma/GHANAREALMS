-- GhanaRealms Store — player accounts + Minecraft account linking
-- Adds what Document 21 ("Account Registration and Minecraft Player ID
-- Verification") needs on top of the existing schema. Does not touch or
-- duplicate admin_users, which is a separate, already-working system.

BEGIN;

-- users.email was already UNIQUE/nullable; player accounts need a real
-- credential + verification state on top of that.
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS password_hash TEXT,
  ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS email_verification_token TEXT,
  ADD COLUMN IF NOT EXISTS password_reset_token TEXT,
  ADD COLUMN IF NOT EXISTS password_reset_expires_at TIMESTAMPTZ;

-- Ties an existing minecraft_players row to a website account, once
-- verified in-game. A minecraft_players row can exist unlinked (e.g. a
-- guest checkout from before this migration, or a purchase made before
-- linking existed) - user_id stays NULL until verification completes.
ALTER TABLE minecraft_players
  ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES users(id),
  ADD COLUMN IF NOT EXISTS verified_at TIMESTAMPTZ;

-- One link code at a time per user; short-lived, single-use. The player
-- gets this code on the website, then proves ownership by entering it
-- in-game (via whatever command the actual GhanaRealms plugin exposes —
-- this table is the website-side half of that handshake; see
-- lib/account-linking.ts and docs/MINECRAFT_BRIDGE.md for what the
-- plugin side still needs to implement).
CREATE TABLE IF NOT EXISTS minecraft_link_codes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code            TEXT UNIQUE NOT NULL,
    minecraft_username TEXT NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    consumed_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_link_codes_code ON minecraft_link_codes (code);

-- One active link per user at a time, enforced at the app layer (a user
-- re-linking supersedes their previous link code) plus here: a given
-- minecraft_players row can only be claimed by one user.
CREATE UNIQUE INDEX IF NOT EXISTS idx_minecraft_players_user_id
  ON minecraft_players (user_id) WHERE user_id IS NOT NULL;

COMMIT;
