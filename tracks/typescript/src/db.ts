// Connection pool + a tiny, transparent SQL migrator (same .sql files as every other track).
// Applied versions are recorded in schema_migrations; an advisory lock prevents races.
import { readdir, readFile } from "node:fs/promises";
import path from "node:path";
import pg from "pg";

export const MIGRATION_LOCK_ID = 7372026;
const FILE_PATTERN = /^V(\d+)__.+\.sql$/;

export function createPool(databaseUrl: string): pg.Pool {
  return new pg.Pool({ connectionString: databaseUrl, max: 10, connectionTimeoutMillis: 5000 });
}

export async function pendingMigrations(dir: string): Promise<{ version: number; file: string }[]> {
  const files = await readdir(dir);
  return files
    .map((file) => ({ file, match: FILE_PATTERN.exec(file) }))
    .filter((f) => f.match)
    .map((f) => ({ version: Number(f.match![1]), file: path.join(dir, f.file) }))
    .sort((a, b) => a.version - b.version);
}

export async function runMigrations(pool: pg.Pool, dir: string): Promise<number[]> {
  const client = await pool.connect();
  const applied: number[] = [];
  try {
    await client.query("SELECT pg_advisory_lock($1)", [MIGRATION_LOCK_ID]);
    await client.query(
      "CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())",
    );
    const { rows } = await client.query<{ version: number }>("SELECT version FROM schema_migrations");
    const done = new Set(rows.map((r) => r.version));
    for (const m of await pendingMigrations(dir)) {
      if (done.has(m.version)) continue;
      const sql = await readFile(m.file, "utf8");
      await client.query("BEGIN");
      try {
        await client.query(sql);
        await client.query("INSERT INTO schema_migrations (version) VALUES ($1)", [m.version]);
        await client.query("COMMIT");
      } catch (err) {
        await client.query("ROLLBACK");
        throw err;
      }
      applied.push(m.version);
    }
  } finally {
    await client.query("SELECT pg_advisory_unlock($1)", [MIGRATION_LOCK_ID]).catch(() => {});
    client.release();
  }
  return applied;
}
