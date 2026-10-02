// Builds the services from settings. Shared by the process entrypoint and the integration tests.
import {
  DescriptionService,
  LlmClient,
  LlmParser,
  LlmWriter,
  RuleParser,
  SearchAssistService,
  TemplateWriter,
} from "./ai.js";
import type { AppDeps } from "./app.js";
import { AuthService } from "./auth.js";
import { CartService } from "./cart.js";
import { CatalogService } from "./catalog.js";
import { systemClock, type Clock } from "./clock.js";
import type { Settings } from "./config.js";
import { LogPublisher, OrderService, OutboxService, PaymentService, type EventPublisher } from "./orders.js";
import type { CartRepository, OrderRepository, ProductRepository, UserRepository } from "./repository.js";
import { newPaymentId, PasswordHasher, resolveSecret, TokenService } from "./security.js";

export interface Ai {
  descriptions: DescriptionService;
  assist: SearchAssistService;
}

export function buildAi(settings: Settings, warn: (msg: string) => void = console.warn): Ai {
  if (settings.aiProvider === "llm") {
    const client = new LlmClient(
      settings.llmBaseUrl,
      settings.llmModel,
      settings.llmApiKey,
      settings.llmTimeoutMs,
    );
    return {
      descriptions: new DescriptionService(new TemplateWriter(), new LlmWriter(client), warn),
      assist: new SearchAssistService(new RuleParser(), new LlmParser(client), warn),
    };
  }
  return {
    descriptions: new DescriptionService(new TemplateWriter(), null, warn),
    assist: new SearchAssistService(new RuleParser(), null, warn),
  };
}

export interface Repos {
  users: UserRepository;
  products: ProductRepository;
  carts: CartRepository;
  orders: OrderRepository;
}

export interface WireOptions {
  clock?: Clock;
  ai?: Ai;
  warn?: (msg: string) => void;
  paymentIds?: () => string;
  publisher?: EventPublisher;
}

export async function wire(settings: Settings, repos: Repos, opts: WireOptions = {}): Promise<AppDeps> {
  const clock = opts.clock ?? systemClock;
  const warn = opts.warn ?? ((msg: string) => console.warn(msg));
  const ai = opts.ai ?? buildAi(settings, warn);
  const tokens = new TokenService(resolveSecret(settings.jwtSecret, warn), clock);
  const auth = new AuthService(repos.users, new PasswordHasher(settings.bcryptCost), tokens, clock);
  if (settings.adminEmail && settings.adminPassword) {
    await auth.ensureAdmin(settings.adminEmail, settings.adminPassword);
  }
  const webhookSecret = resolveSecret(settings.paymentWebhookSecret, warn, "PAYMENT_WEBHOOK_SECRET");
  return {
    auth,
    catalog: new CatalogService(repos.products, clock),
    cart: new CartService(repos.carts, repos.products, clock),
    orders: new OrderService(repos.orders, repos.carts, clock, opts.paymentIds ?? newPaymentId),
    payments: new PaymentService(repos.orders, webhookSecret, clock),
    outbox: new OutboxService(repos.orders, opts.publisher ?? new LogPublisher(), clock, warn),
    descriptions: ai.descriptions,
    assist: ai.assist,
    clock,
  };
}
