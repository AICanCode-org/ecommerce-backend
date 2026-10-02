// Configuration from environment variables (12-factor). Same names in every track.
import { fileURLToPath } from "node:url";
import path from "node:path";

export interface Settings {
  databaseUrl: string;
  port: number;
  migrationsDir: string;
  /** null -> a random per-process secret (dev only, logged as a warning) */
  jwtSecret: string | null;
  bcryptCost: number;
  adminEmail: string | null;
  adminPassword: string | null;
  /** null -> a random per-process secret (dev only) */
  paymentWebhookSecret: string | null;
  aiProvider: "offline" | "llm";
  llmBaseUrl: string;
  llmApiKey: string | null;
  llmModel: string;
  llmTimeoutMs: number;
}

const here = path.dirname(fileURLToPath(import.meta.url));
// tracks/typescript/src -> <repo>/spec/db/migrations (only used outside Docker)
const defaultMigrations = path.resolve(here, "..", "..", "..", "spec", "db", "migrations");

export function loadSettings(env: NodeJS.ProcessEnv = process.env): Settings {
  return {
    databaseUrl: env.DATABASE_URL ?? "postgresql://shopflow:shopflow@localhost:5432/shopflow",
    port: Number(env.PORT ?? 8080),
    migrationsDir: env.MIGRATIONS_DIR || defaultMigrations,
    jwtSecret: env.JWT_SECRET || null,
    bcryptCost: Number(env.BCRYPT_COST ?? 10),
    adminEmail: env.ADMIN_EMAIL || null,
    adminPassword: env.ADMIN_PASSWORD || null,
    paymentWebhookSecret: env.PAYMENT_WEBHOOK_SECRET || null,
    aiProvider: (env.AI_PROVIDER ?? "offline").toLowerCase() === "llm" ? "llm" : "offline",
    llmBaseUrl: (env.LLM_BASE_URL ?? "http://localhost:11434/v1").replace(/\/+$/, ""),
    llmApiKey: env.LLM_API_KEY || null,
    llmModel: env.LLM_MODEL ?? "llama3.2:1b",
    llmTimeoutMs: Number(env.LLM_TIMEOUT_MS ?? 5000),
  };
}
