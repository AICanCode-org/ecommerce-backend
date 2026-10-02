-- ShopFlow v1.1 (Phase 7): unpaid orders hold their stock for a limited time.
-- Every order gets a payment window (RESERVATION_TTL_MINUTES = 15). An admin job,
-- POST /api/v1/admin/orders/expire, cancels PENDING_PAYMENT orders whose window has closed and
-- puts their stock back, so abandoned checkouts no longer lock inventory forever.
--
-- Expand-only and backwards compatible: v1.0 does not know the column and keeps working on this schema
-- (its inserts get the DEFAULT), which is what a rolling deploy needs (see docs/phase-7).
ALTER TABLE orders ADD COLUMN expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '15 minutes');
UPDATE orders SET expires_at = created_at + INTERVAL '15 minutes';

-- The expiry job only ever looks at unpaid orders.
CREATE INDEX idx_orders_payment_window ON orders (expires_at) WHERE status = 'PENDING_PAYMENT';
