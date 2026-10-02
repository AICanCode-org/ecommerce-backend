-- ShopFlow V1 schema (Phase 2). Shared by the Java, Python and TypeScript tracks.
-- Never edit this file after it is merged: add V2__... instead.
-- Money is always BIGINT paise (1 rupee = 100 paise). Never FLOAT/NUMERIC-in-code for money (ADR-0001).

CREATE TABLE users (
    id            BIGSERIAL    PRIMARY KEY,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,           -- bcrypt, never the password itself
    display_name  VARCHAR(50)  NOT NULL,
    role          VARCHAR(10)  NOT NULL DEFAULT 'CUSTOMER',
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_lower CHECK (email = lower(email)),
    CONSTRAINT ck_users_role CHECK (role IN ('CUSTOMER', 'ADMIN'))
);

CREATE TABLE products (
    id          BIGSERIAL    PRIMARY KEY,
    sku         VARCHAR(32)  NOT NULL,
    name        VARCHAR(120) NOT NULL,
    description TEXT         NOT NULL DEFAULT '',
    category    VARCHAR(30)  NOT NULL,
    price_paise BIGINT       NOT NULL,
    stock       INTEGER      NOT NULL DEFAULT 0,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    version     INTEGER      NOT NULL DEFAULT 1,   -- optimistic locking for admin edits (ADR-0003)
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', name || ' ' || description)) STORED,
    CONSTRAINT uq_products_sku UNIQUE (sku),
    CONSTRAINT ck_products_price CHECK (price_paise > 0 AND price_paise <= 100000000),
    -- The last line of defence against overselling: the database refuses negative stock (ADR-0002).
    CONSTRAINT ck_products_stock CHECK (stock >= 0),
    CONSTRAINT ck_products_description_length CHECK (char_length(description) <= 2000)
);
CREATE INDEX idx_products_catalog ON products (id) WHERE active;
CREATE INDEX idx_products_category ON products (category) WHERE active;
CREATE INDEX idx_products_search ON products USING GIN (search_vector);

CREATE TABLE coupons (
    code               VARCHAR(20) PRIMARY KEY,
    kind               VARCHAR(10) NOT NULL,
    value              BIGINT      NOT NULL,       -- PERCENT: 1..90 · FLAT: paise
    min_subtotal_paise BIGINT      NOT NULL DEFAULT 0,
    max_discount_paise BIGINT,                     -- PERCENT only: cap, NULL = no cap
    active             BOOLEAN     NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_coupons_kind CHECK (kind IN ('PERCENT', 'FLAT')),
    CONSTRAINT ck_coupons_value CHECK (value > 0 AND (kind = 'FLAT' OR value <= 90))
);
-- Demo coupons (same in every track's in-memory store). Synthetic, for learning only.
INSERT INTO coupons (code, kind, value, min_subtotal_paise, max_discount_paise, active) VALUES
    ('SAVE10',   'PERCENT', 10,    0,     20000, TRUE),   -- 10 % off, at most ₹200
    ('FLAT100',  'FLAT',    10000, 50000, NULL,  TRUE),   -- ₹100 off orders of ₹500+
    ('EXPIRED5', 'PERCENT', 5,     0,     NULL,  FALSE);  -- switched off: applying it fails

-- One cart per customer, kept in the database (survives restarts, works across devices).
CREATE TABLE carts (
    user_id     BIGINT      PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    coupon_code VARCHAR(20) REFERENCES coupons (code),
    updated_at  TIMESTAMPTZ NOT NULL
);

CREATE TABLE cart_items (
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    product_id BIGINT      NOT NULL REFERENCES products (id),
    quantity   INTEGER     NOT NULL,
    added_at   TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (user_id, product_id),
    CONSTRAINT ck_cart_items_quantity CHECK (quantity BETWEEN 1 AND 10)
);

CREATE TABLE orders (
    id              BIGSERIAL   PRIMARY KEY,
    user_id         BIGINT      NOT NULL REFERENCES users (id),
    status          VARCHAR(20) NOT NULL,
    subtotal_paise  BIGINT      NOT NULL,
    discount_paise  BIGINT      NOT NULL DEFAULT 0,
    shipping_paise  BIGINT      NOT NULL DEFAULT 0,
    total_paise     BIGINT      NOT NULL,
    coupon_code     VARCHAR(20),
    idempotency_key VARCHAR(64) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    -- A retried checkout with the same key can never create a second order (ADR-0004).
    CONSTRAINT uq_orders_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_orders_status CHECK (status IN ('PENDING_PAYMENT', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
    CONSTRAINT ck_orders_total CHECK (total_paise = subtotal_paise - discount_paise + shipping_paise AND total_paise >= 0)
);
CREATE INDEX idx_orders_user ON orders (user_id, id DESC);
CREATE INDEX idx_orders_status ON orders (status, id DESC);

-- Name, SKU and price are copied: an order must not change when the product is edited later.
CREATE TABLE order_items (
    order_id         BIGINT       NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    line_no          INTEGER      NOT NULL,
    product_id       BIGINT       NOT NULL REFERENCES products (id),
    sku              VARCHAR(32)  NOT NULL,
    name             VARCHAR(120) NOT NULL,
    unit_price_paise BIGINT       NOT NULL,
    quantity         INTEGER      NOT NULL,
    PRIMARY KEY (order_id, line_no),
    CONSTRAINT ck_order_items_quantity CHECK (quantity > 0)
);

CREATE TABLE payments (
    id           VARCHAR(40) PRIMARY KEY,          -- pay_<random>, shared with the payment provider
    order_id     BIGINT      NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    provider     VARCHAR(20) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    amount_paise BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_payments_order UNIQUE (order_id),
    CONSTRAINT ck_payments_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED'))
);

-- Every webhook event id we have processed: a redelivered event is recognised and ignored (ADR-0004).
CREATE TABLE payment_events (
    event_id    VARCHAR(64) PRIMARY KEY,
    payment_id  VARCHAR(40) NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
    type        VARCHAR(30) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL
);

-- Transactional outbox (ADR-0005): written in the SAME transaction as the order change,
-- published later by a relay. At-least-once delivery: consumers de-duplicate on id.
CREATE TABLE outbox_events (
    id           BIGSERIAL   PRIMARY KEY,
    type         VARCHAR(40) NOT NULL,
    order_id     BIGINT      NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    payload      JSONB       NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ
);
CREATE INDEX idx_outbox_pending ON outbox_events (id) WHERE published_at IS NULL;
