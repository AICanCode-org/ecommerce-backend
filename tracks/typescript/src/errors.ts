// Domain errors that map 1:1 to RFC 9457 problem responses (identical in every track).
export const PROBLEM_BASE = "https://shopflow.example/problems/";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly slug: string,
    readonly title: string,
    readonly detail?: string,
  ) {
    super(detail ?? title);
  }

  toProblem(): Record<string, unknown> {
    const body: Record<string, unknown> = {
      type: PROBLEM_BASE + this.slug,
      title: this.title,
      status: this.status,
    };
    if (this.detail) body.detail = this.detail;
    return body;
  }
}

export const validationError = (detail: string) =>
  new ApiError(400, "validation-error", "Validation failed", detail);
export const unauthorized = (detail = "authentication required") =>
  new ApiError(401, "unauthorized", "Unauthorized", detail);
export const invalidCredentials = () =>
  new ApiError(401, "invalid-credentials", "Invalid credentials", "wrong e-mail or password");
export const invalidSignature = () =>
  new ApiError(401, "invalid-signature", "Invalid signature", "webhook signature is invalid");
export const forbidden = (detail = "you are not allowed to do this") =>
  new ApiError(403, "forbidden", "Forbidden", detail);
export const notFound = (what: string) => new ApiError(404, "not-found", "Not found", `${what} not found`);
export const emailTaken = () =>
  new ApiError(409, "email-taken", "E-mail already registered", "an account with this e-mail exists");
export const skuTaken = () =>
  new ApiError(409, "sku-taken", "SKU already exists", "a product with this SKU exists");
export const versionConflict = (current: number) =>
  new ApiError(
    409,
    "version-conflict",
    "Version conflict",
    `the product was changed by someone else (current version ${current})`,
  );
export const insufficientStock = (detail: string) =>
  new ApiError(409, "insufficient-stock", "Insufficient stock", detail);
export const cartEmpty = () =>
  new ApiError(409, "cart-empty", "Cart is empty", "add something to your cart before checking out");
export const invalidTransition = (detail: string) =>
  new ApiError(409, "invalid-transition", "Invalid order transition", detail);

/** Thrown by a repository when a unique constraint (e-mail, SKU, idempotency key) is violated. */
export class DuplicateError extends Error {}

/** Thrown by OrderRepository.placeOrder when a guarded stock decrement fails (nothing is written). */
export class InsufficientStockError extends Error {
  constructor(readonly sku: string) {
    super(sku);
  }
}
