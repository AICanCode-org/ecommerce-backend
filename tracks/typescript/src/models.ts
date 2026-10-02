// Plain data types. No framework types leak into the domain.
export const ROLES = ["CUSTOMER", "ADMIN"] as const;
export type Role = (typeof ROLES)[number];
export const ORDER_STATUSES = ["PENDING_PAYMENT", "PAID", "SHIPPED", "DELIVERED", "CANCELLED"] as const;
export type OrderStatus = (typeof ORDER_STATUSES)[number];
export type PaymentStatus = "PENDING" | "SUCCEEDED" | "FAILED";

export interface User {
  id: number;
  email: string;
  passwordHash: string;
  displayName: string;
  role: Role;
  createdAt: Date;
}

/** Who is calling, taken from a verified access token. */
export interface Principal {
  userId: number;
  role: Role;
}

export interface LoginResult {
  accessToken: string;
  user: User;
}

export interface Product {
  id: number;
  sku: string;
  name: string;
  description: string;
  category: string;
  pricePaise: number;
  stock: number;
  active: boolean;
  version: number;
  createdAt: Date;
  updatedAt: Date;
}

/** Catalog filters. Only active products are ever listed. Ordered by id; afterId is the keyset cursor. */
export interface ProductQuery {
  limit: number;
  afterId?: number | null;
  q?: string | null;
  category?: string | null;
  minPricePaise?: number | null;
  maxPricePaise?: number | null;
  inStock?: boolean | null;
}

export interface CategoryCount {
  name: string;
  products: number;
}

export interface Coupon {
  code: string;
  kind: "PERCENT" | "FLAT";
  value: number;
  minSubtotalPaise: number;
  maxDiscountPaise: number | null;
  active: boolean;
}

export interface CartLine {
  product: Product;
  quantity: number;
}

export interface CartState {
  lines: CartLine[];
  couponCode: string | null;
}

export interface Pricing {
  subtotalPaise: number;
  discountPaise: number;
  shippingPaise: number;
  totalPaise: number;
  couponApplied: boolean;
}

export interface CartView extends CartState {
  pricing: Pricing;
}

export interface OrderLine {
  productId: number;
  sku: string;
  name: string;
  unitPricePaise: number;
  quantity: number;
}

export interface Payment {
  id: string;
  orderId: number;
  provider: string;
  status: PaymentStatus;
  amountPaise: number;
  createdAt: Date;
  updatedAt: Date;
}

export interface Order {
  id: number;
  userId: number;
  status: OrderStatus;
  lines: OrderLine[];
  subtotalPaise: number;
  discountPaise: number;
  shippingPaise: number;
  totalPaise: number;
  couponCode: string | null;
  idempotencyKey: string;
  payment: Payment;
  createdAt: Date;
  updatedAt: Date;
  expiresAt: Date; // v1.1: end of the payment window
}

/** Everything placeOrder needs to write in ONE transaction. */
export interface OrderDraft {
  userId: number;
  idempotencyKey: string;
  lines: OrderLine[];
  pricing: Pricing;
  couponCode: string | null;
  paymentId: string;
  at: Date;
  expiresAt: Date; // v1.1: at + RESERVATION_TTL
}

export interface NewEvent {
  type: string;
  payload: Record<string, unknown>;
}

/** One atomic state change: order status (compare-and-set), stock, payment, webhook event, outbox. */
export interface Transition {
  orderId: number;
  fromStatus: OrderStatus;
  toStatus: OrderStatus | null; // null: the order itself does not change
  at: Date;
  restock?: boolean;
  events?: NewEvent[];
  paymentId?: string | null;
  paymentStatus?: PaymentStatus | null;
  webhookEventId?: string | null;
  webhookEventType?: string | null;
}

export type TransitionResult = "ok" | "duplicate" | "conflict";

export interface OutboxEvent {
  id: number;
  type: string;
  orderId: number;
  payload: Record<string, unknown>;
  createdAt: Date;
  publishedAt: Date | null;
}

export interface SearchFilters {
  terms: string[];
  category: string | null;
  minPricePaise: number | null;
  maxPricePaise: number | null;
  inStock: boolean | null;
}
