# Contributing to ShopFlow

This repository is a **learning project** from [AiCanCode](https://www.aicancode.org). Students work in
their own fork; maintainers improve the starter, the docs and the reference solution here.

## Workflow (students and maintainers alike)
1. **One issue per change.** Use the *User story* or *Bug report* template.
2. **Branch from `main`:** `git switch -c feat/us-5-checkout` (`feat/`, `fix/`, `docs/`, `chore/`).
3. **Small commits, [Conventional Commits](https://www.conventionalcommits.org/) messages:**
   `feat(python): guarded stock decrement`, `test(java): other customer's order is 404`, `docs: phase 3 M2`.
4. **Run the checks locally before pushing** — exactly what CI runs:
   ```bash
   cd tracks/<java|python|typescript>
   make lint test            # fast, no Docker needed
   make test-integration     # needs: docker compose up -d postgres
   ```
5. **Open a pull request** and fill in the template, including the *AI assistance* section.
6. **CI must be green** and the PR self-reviewed against the checklist before merge (squash merge).

## Code style
| Track | Formatter / linter | Command |
|---|---|---|
| Java | Checkstyle (`tracks/java/checkstyle.xml`) | `make lint` |
| Python | Ruff (lint + format) | `make lint` · auto-fix: `.venv/bin/ruff format app tests` |
| TypeScript | ESLint + Prettier + `tsc --noEmit` | `make lint` · auto-fix: `npm run format` |

## Keep the three tracks in sync (maintainers)
* `spec/openapi.yaml` is the contract. A behaviour change = spec change + all three tracks + smoke test.
* Error messages, problem types, the description template, the search-assist rules and metric names are identical in every track.
* The `solution` branch has one annotated tag per phase. If you change an early phase, rebase the
  later phase commits and **move the tags**, then run `scripts/verify-tags.sh` — every tag must pass.

## Security-sensitive changes
Anything that touches passwords, tokens, roles or ownership checks needs a test that proves the
*refusal* (wrong role, other customer's order, bad signature, insufficient stock, replayed key), not only the happy path. Never log a
password, an access token, a webhook secret or a signature.

## Using AI assistants
Allowed and encouraged, with the rules in [`AI_LOG.md`](AI_LOG.md): no secrets in prompts, verify
every output, log what you kept and rejected.
