-- Server-first account claiming: a player runs /store in-game; the plugin asks
-- the store for a one-time token tied to their Mojang-verified UUID. Only a
-- SHA-256 hash of the token is stored.
BEGIN;
CREATE TABLE IF NOT EXISTS minecraft_claim_tokens (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash         TEXT UNIQUE NOT NULL,
    minecraft_uuid     UUID NOT NULL,
    minecraft_username TEXT NOT NULL,
    expires_at         TIMESTAMPTZ NOT NULL,
    consumed_at        TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_claim_tokens_uuid ON minecraft_claim_tokens (minecraft_uuid);
COMMIT;
