# ADR-0002: Guarded stock decrement instead of read-check-write

* **Status:** accepted
* **Date:** 2026-10-02

## Context
Last Diwali the shop sold the same last bag twice (threat T3). The naive code reads the stock, checks
it in the application and writes the new value. Two requests that read `stock = 1` at the same time
both pass the check and both write `0`: two orders, one bag.

## Options considered
1. **Read, check, write** in the application: has the race described above.
2. **Pessimistic locking** (`SELECT … FOR UPDATE`, then check, then update): correct, but two round
   trips per line, and easy to forget the `FOR UPDATE` in one code path.
3. **A guarded decrement**: one statement that only succeeds when there is enough:
   `UPDATE products SET stock = stock - :q WHERE id = :id AND active AND stock >= :q`.
   Zero rows updated = not enough stock.
4. **Reserve stock in a separate table / queue**: needed for flash sales at very large scale; much
   more machinery than a small shop needs.

## Decision
Option 3, inside the checkout transaction, one statement per order line; any line that matches zero
rows rolls the whole transaction back and returns `409 insufficient-stock` (all-or-nothing). The
database also has `CHECK (stock >= 0)` as the last line of defence, and admin stock moves use the same
guarded form (`stock + delta >= 0`).

## Consequences
* No overselling under any concurrency; PostgreSQL's row lock serialises competing decrements.
* The cart does **not** reserve stock: an item in a cart can sell out before checkout (the customer
  gets a clear 409). Reserving stock for unpaid orders for a limited time is added in v1.1 (V2).
* The integration tests check it with parallel checkouts for the last unit.
