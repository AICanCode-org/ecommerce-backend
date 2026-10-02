# Database migrations (shared by all three tracks)

Put plain SQL files here named `V<version>__<description>.sql`, for example `V1__init.sql`.

* Every track ships a tiny migrator (≈40 lines) that runs **at application start-up**:
  it creates `schema_migrations`, takes a Postgres advisory lock so two instances never race,
  and applies every file whose version is not recorded yet, each in its own transaction.
* Files are **immutable once merged**: never edit an applied migration, add `V2__...` instead.
* The same files are copied into each Docker image at `/app/migrations` (`MIGRATIONS_DIR`).

You write `V1__init.sql` in **Phase 2** (see `docs/phase-2-design.md`).
