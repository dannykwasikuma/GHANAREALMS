-- Catalogue schema: two separate price systems (real money GH₵ and in-game currency),
-- stock, purchase limits, discounts, availability windows, gameplay-only items.
BEGIN;

ALTER TABLE products ALTER COLUMN price_pesewas DROP NOT NULL;      -- NULL = not sold for real money
ALTER TABLE products
  ADD COLUMN ingame_price_minor BIGINT CHECK (ingame_price_minor IS NULL OR ingame_price_minor > 0), -- NULL = not sold for in-game currency
  ADD COLUMN item_specs TEXT[] NOT NULL DEFAULT '{}',   -- items handed over, `gritem` spec format (see minecraft/GhanaRealmsItems)
  ADD COLUMN icon_material TEXT,                         -- Minecraft material used as the product icon
  ADD COLUMN stock INT CHECK (stock IS NULL OR stock >= 0), -- NULL = unlimited, shared by web + in-game sales
  ADD COLUMN purchase_limit_per_player INT CHECK (purchase_limit_per_player IS NULL OR purchase_limit_per_player > 0),
  ADD COLUMN limit_window_days INT CHECK (limit_window_days IS NULL OR limit_window_days > 0), -- NULL = lifetime limit
  ADD COLUMN discount_percent INT NOT NULL DEFAULT 0 CHECK (discount_percent BETWEEN 0 AND 90),
  ADD COLUMN discount_ends_at TIMESTAMPTZ,
  ADD COLUMN gameplay_only BOOLEAN NOT NULL DEFAULT false, -- shown in catalogue, never purchasable
  ADD COLUMN season TEXT,
  ADD COLUMN available_from TIMESTAMPTZ,
  ADD COLUMN available_until TIMESTAMPTZ;

ALTER TABLE products ADD CONSTRAINT products_has_a_price
  CHECK (price_pesewas IS NOT NULL OR ingame_price_minor IS NOT NULL OR gameplay_only);

CREATE INDEX idx_products_category_active ON products (category, active);

-- In-game (Vault currency) purchases recorded centrally so stock and per-player
-- limits are shared between the website and the Minecraft server.
CREATE TABLE ingame_purchases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id UUID NOT NULL REFERENCES products(id),
    minecraft_player_id UUID NOT NULL REFERENCES minecraft_players(id),
    quantity INT NOT NULL CHECK (quantity > 0),
    unit_price_minor BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ingame_purchases_player_product ON ingame_purchases (minecraft_player_id, product_id, created_at);

COMMIT;
