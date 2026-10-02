// Process entrypoint: wire real adapters, run migrations, start listening on $PORT.
import { buildApp } from "./app.js";
import { loadSettings } from "./config.js";
import { createPool, runMigrations } from "./db.js";
import { PgCartRepository, PgOrderRepository, PgProductRepository, PgUserRepository } from "./repository.js";
import { wire } from "./wiring.js";

const settings = loadSettings();
const pool = createPool(settings.databaseUrl);
const applied = await runMigrations(pool, settings.migrationsDir);
console.log(`migrations applied this start: ${applied.length ? applied.join(",") : "none"}`);

const deps = await wire(settings, {
  users: new PgUserRepository(pool),
  products: new PgProductRepository(pool),
  carts: new PgCartRepository(pool),
  orders: new PgOrderRepository(pool),
});
if (settings.adminEmail && settings.adminPassword)
  console.log(`admin account ensured for ${settings.adminEmail}`);
const app = buildApp(deps, { logger: true });

const shutdown = async () => {
  await app.close();
  await pool.end();
  process.exit(0);
};
process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);

await app.listen({ host: "0.0.0.0", port: settings.port });
