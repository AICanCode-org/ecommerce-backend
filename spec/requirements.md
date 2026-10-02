# ShopFlow requirements

> **Phase 1 deliverable.** Replace every `TODO` below. Keep it short: a reviewer should be able to
> read this in ten minutes. The reference version is on the `solution` branch at tag `phase-1-end`
> (`git show phase-1-end:spec/requirements.md`). Try on your own first!

## 1. Problem statement
TODO: who has the problem (a small Indian shop selling online), what it is, why a hosted storefront
or a marketplace doesn't fit (2-3 sentences).

## 2. Users / personas and roles
TODO: at least three personas mapped to anonymous visitors and the roles CUSTOMER and ADMIN, plus a
permission matrix (who may do what, and what a refused request returns: 401, 403 or 404).

## 3. Functional requirements (FR)
TODO: numbered, testable statements ("The system shall ..."). Cover register/login, roles, the
catalog (browse, search, admin edits, stock), the cart and coupons, checkout, orders and their
states, the payment webhook, the outbox, the two AI helpers and health.

## 4. Non-functional requirements (NFR)
TODO: correctness of money, no overselling, security, performance, availability, privacy, cost,
portability. Make each one measurable.

## 5. Threats (what could go wrong?)
TODO: at least six abuse cases (e.g. a forged "payment succeeded" webhook, a double-clicked "Pay"
button, two customers buying the last item, mass assignment of `role`, reading someone else's order
by guessing its id, prompt injection through the search box) and the requirement that answers each.

## 6. User stories + acceptance criteria
TODO: at least 8 stories in the form *As a ... I want ... so that ...*, each with Given/When/Then
criteria. Create one GitHub Issue per story using the "User story" issue template.

## 7. Out of scope (v1)
TODO

## 8. Back-of-the-envelope estimates
TODO: products, visitors/day, orders/day, peak orders/minute (a festival sale!), read:write ratio,
storage per year, peak requests/s.
