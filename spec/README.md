# ShopFlow shared specification

Everything in this folder is **language-agnostic**. The Java, Python and TypeScript tracks all
implement exactly this, and the same black-box smoke test (`scripts/smoke-test.sh`, Phase 6) runs
against each.

| File | What it is | Written in |
|---|---|---|
| `requirements.md` | Functional + non-functional requirements, threats, user stories, acceptance criteria | Phase 1 |
| `architecture.md` | Mermaid diagrams (context, containers, checkout + payment sequences, order states, data model) | Phase 2 |
| `adr/` | Architecture Decision Records (one decision per file) | Phase 2+ |
| `openapi.yaml` | The HTTP contract (OpenAPI 3.1). **Provided** on `main` so all tracks agree | given |
| `db/migrations/` | SQL migrations shared by every track | Phase 2 (V1), Phase 7 (V2) |
| `db/init/` | Runs once when the local Postgres volume is created (creates `shopflow_test`) | given |

Validate the contract locally with any OpenAPI linter, e.g.
`pipx run openapi-spec-validator spec/openapi.yaml` (CI does the same from Phase 5).
