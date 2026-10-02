# ADR-0003: Optimistic locking for product edits

* **Status:** accepted
* **Date:** 2026-10-02

## Context
The owner and his assistant both edit products. Editing a description takes minutes, saving takes
milliseconds. If both load version 3 and save, the second save silently overwrites the first (a lost
update, threat T7), for example a corrected price.

## Options considered
1. **Last write wins**: simplest; loses data.
2. **Pessimistic lock** while the edit form is open: cannot be held across HTTP requests without lock
   tables, timeouts and "who locked it?" support questions.
3. **Optimistic locking**: a `version` column; clients send the version they loaded; the update is
   `… SET version = version + 1 WHERE id = ? AND version = ?`.

## Decision
Option 3. `version` is required on `PATCH /api/v1/products/{sku}`. Zero updated rows means someone
else saved first: `409 version-conflict` stating the current version, so the client can reload and
re-apply. The check happens in the same statement as the update: no race between check and write.

**Stock is not part of this.** Checkouts change stock all the time; if they bumped the version, every
admin edit during a sale would conflict. Stock moves use the guarded decrement (ADR-0002) and leave
the version alone.

## Consequences
* No lost edits, no held locks, one extra integer per row.
* Clients must handle 409; the contract documents it and the smoke test exercises it.
