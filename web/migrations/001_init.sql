-- GhanaRealms Store - initial schema
-- Run with: psql "$DATABASE_URL" -f migrations/001_init.sql

CREATE EXTENSION IF NOT EXISTS pgcrypto; -- gen_random_uuid()

-- ============================================================
-- users: web accounts (optional - checkout does not require one,
-- per the brief's "do not force account creation")
-- ============================================================
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email TEXT UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================
-- minecraft_players: the actual identity a purchase is delivered to.
-- UUID is nullable because we can only look it up server-side (see
-- lib/mojang.ts) - a brand new/typo'd username won't resolve to one
-- until validated, but we still want the order to exist.
-- ============================================================
CREATE TABLE minecraft_players (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username TEXT NOT NULL,
    minecraft_uuid UUID,
    user_id UUID REFERENCES users(id) ON DELETE SET NULL,
    first_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (minecraft_uuid)
);
CREATE INDEX idx_minecraft_players_username ON minecraft_players (lower(username));

-- ============================================================
-- products: database-driven, per section 8. Prices in GHS pesewas
-- (integer, smallest unit) to avoid float rounding on money - same
-- reasoning as GhanaRealmsPaystack's Java side already used.
-- ============================================================
CREATE TABLE products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    slug TEXT NOT NULL UNIQUE,
    description TEXT NOT NULL DEFAULT '',
    price_pesewas BIGINT NOT NULL CHECK (price_pesewas > 0),
    currency TEXT NOT NULL DEFAULT 'GHS',
    category TEXT NOT NULL DEFAULT 'rank',
    image_url TEXT,
    rank_permission TEXT, -- e.g. a LuckPerms group name; NULL if this product isn't a rank
    duration_days INT, -- NULL = permanent
    delivery_commands TEXT[] NOT NULL DEFAULT '{}', -- console commands run on delivery, {player} placeholder
    give_money_minor BIGINT NOT NULL DEFAULT 0, -- in-game currency to grant, if any
    active BOOLEAN NOT NULL DEFAULT true,
    featured BOOLEAN NOT NULL DEFAULT false,
    sort_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_products_active_featured ON products (active, featured, sort_order);

-- ============================================================
-- orders
-- ============================================================
CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_number TEXT NOT NULL UNIQUE, -- short human-shareable id for /lookup
    minecraft_player_id UUID NOT NULL REFERENCES minecraft_players(id),
    status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','PAID','FAILED','CANCELLED','REFUNDED')),
    total_pesewas BIGINT NOT NULL CHECK (total_pesewas > 0),
    currency TEXT NOT NULL DEFAULT 'GHS',
    terms_version TEXT NOT NULL,
    privacy_version TEXT NOT NULL,
    refund_policy_version TEXT NOT NULL,
    terms_accepted_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_orders_minecraft_player ON orders (minecraft_player_id);
CREATE INDEX idx_orders_status ON orders (status);

-- ============================================================
-- order_items: one order can (in principle) contain multiple products,
-- even though the initial checkout UI only supports one at a time.
-- ============================================================
CREATE TABLE order_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES products(id),
    unit_price_pesewas BIGINT NOT NULL, -- snapshot at purchase time - price changes later must not alter old orders
    quantity INT NOT NULL DEFAULT 1 CHECK (quantity > 0)
);
CREATE INDEX idx_order_items_order ON order_items (order_id);

-- ============================================================
-- payments: one row per Paystack transaction attempt for an order
-- ============================================================
CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id),
    paystack_reference TEXT NOT NULL UNIQUE, -- the actual idempotency key
    status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','PAID','FAILED','ABANDONED')),
    amount_pesewas BIGINT NOT NULL,
    currency TEXT NOT NULL DEFAULT 'GHS',
    channel TEXT, -- 'card', 'mobile_money', etc - filled in from Paystack's response, never hardcoded
    paystack_customer_email TEXT NOT NULL, -- placeholder email, see lib/paystack.ts
    verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payments_order ON payments (order_id);

-- ============================================================
-- payment_events: raw webhook log, for idempotency + audit. The
-- UNIQUE constraint on (paystack_reference, event_type) is the actual
-- duplicate-webhook protection - Paystack retries are expected and
-- must be safely ignorable.
-- ============================================================
CREATE TABLE payment_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    paystack_reference TEXT NOT NULL,
    event_type TEXT NOT NULL,
    raw_payload JSONB NOT NULL,
    processed BOOLEAN NOT NULL DEFAULT false,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (paystack_reference, event_type)
);

-- ============================================================
-- delivery_queue: what the Minecraft bridge polls. A row here is only
-- created once a payment is confirmed PAID - never before.
-- ============================================================
CREATE TABLE delivery_queue (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id UUID NOT NULL REFERENCES orders(id),
    order_item_id UUID NOT NULL REFERENCES order_items(id),
    minecraft_player_id UUID NOT NULL REFERENCES minecraft_players(id),
    status TEXT NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','PROCESSING','DELIVERED','FAILED','RETRYING')),
    attempt_count INT NOT NULL DEFAULT 0,
    last_error TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    delivered_at TIMESTAMPTZ,
    -- one delivery per order_item, ever - this is the duplicate-delivery guard
    UNIQUE (order_item_id)
);
CREATE INDEX idx_delivery_queue_pending ON delivery_queue (status) WHERE status IN ('PENDING','RETRYING');
CREATE INDEX idx_delivery_queue_player ON delivery_queue (minecraft_player_id);

-- ============================================================
-- delivery_attempts: full history, so a failure has a paper trail
-- ============================================================
CREATE TABLE delivery_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    delivery_queue_id UUID NOT NULL REFERENCES delivery_queue(id) ON DELETE CASCADE,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    success BOOLEAN NOT NULL,
    error TEXT
);
CREATE INDEX idx_delivery_attempts_queue ON delivery_attempts (delivery_queue_id);

-- ============================================================
-- admin_users
-- ============================================================
CREATE TABLE admin_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL, -- bcrypt
    role TEXT NOT NULL DEFAULT 'admin' CHECK (role IN ('admin','superadmin')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ
);

-- ============================================================
-- audit_logs: admin actions only (product edits, refunds, etc) -
-- not general request logging
-- ============================================================
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_user_id UUID REFERENCES admin_users(id),
    action TEXT NOT NULL,
    target_type TEXT,
    target_id UUID,
    details JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_audit_logs_admin ON audit_logs (admin_user_id);
