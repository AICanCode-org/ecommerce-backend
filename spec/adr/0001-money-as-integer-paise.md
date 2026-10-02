# ADR-0001: Money as integer paise

* **Status:** accepted
* **Date:** 2026-10-02

## Context
Prices, discounts, shipping and totals are added, multiplied by quantities and compared with
thresholds ("free shipping from ₹999"). Binary floating point cannot represent most decimal amounts:
`0.1 + 0.2 != 0.3`, and `19.99 * 3` is `59.97000000000001`. One paisa off on a total is a support
ticket; a threshold check that flips because of it is a pricing bug.

## Options considered
1. **Float/double rupees**: what most tutorials do; wrong.
2. **Decimal types** (`BigDecimal`, Python `Decimal`, a JS decimal library) with `NUMERIC(12,2)`:
   correct, but every track needs a different library, JSON has no decimal type (numbers arrive as
   floats in JavaScript), and rounding modes must be configured everywhere.
3. **Integers in the smallest unit (paise)** everywhere: API, code and database (`BIGINT`).

## Decision
Option 3. Every amount is an integer number of paise; every field name ends in `Paise`. Percent
discounts use integer arithmetic and round **down** (`subtotal * percent / 100`), so the shop never
gives away more than advertised. Rupees appear only when formatting for humans (`₹1,23,456.78`).

## Consequences
* Exact and identical in Java (`long`), Python (`int`) and TypeScript (`number`, exact up to 2^53, far
  above any price we allow: max ₹10 lakh × 10 × 20 lines).
* The database double-checks the arithmetic: `CHECK (total_paise = subtotal_paise − discount_paise +
  shipping_paise)`.
* Clients must divide by 100 for display; the contract says so in every description.
* Multi-currency would need a currency column and per-currency minor units (out of scope).
