// The cart: one per signed-in user, stored in the database, priced live with current prices.
import type { Clock } from "./clock.js";
import type { CartView, Principal } from "./models.js";
import type { CartRepository, ProductRepository } from "./repository.js";

export const MAX_LINES = 20;

export class CartService {
  constructor(
    readonly carts: CartRepository,
    private readonly products: ProductRepository,
    private readonly clock: Clock,
  ) {}

  async view(principal: Principal): Promise<CartView> {
    // TODO(phase-3 M4): the cart lines + coupon code, priced with price_cart (the coupon may no longer apply:
    //   then couponApplied is false).
    throw new Error("TODO(phase-3 M4): CartService.view");
  }

  async setItem(principal: Principal, sku: string, payload: unknown): Promise<CartView> {
    // TODO(phase-3 M4): validate quantity; the product must exist and be active (404); quantity > stock ->
    //   409 insufficient-stock; a NEW line when the cart already has MAX_LINES lines -> 400; upsert the line
    //   (it sets the quantity, it does not add) and return the view.
    throw new Error("TODO(phase-3 M4): CartService.setItem");
  }

  async removeItem(principal: Principal, sku: string): Promise<CartView> {
    // TODO(phase-3 M4): unknown sku -> 404; removing a line that is not in the cart is fine (idempotent);
    //   return the view.
    throw new Error("TODO(phase-3 M4): CartService.removeItem");
  }

  async applyCoupon(principal: Principal, payload: unknown): Promise<CartView> {
    // TODO(phase-3 M4): validate the code; unknown or inactive coupon -> 404 not-found 'coupon'; store it on
    //   the cart and return the view.
    throw new Error("TODO(phase-3 M4): CartService.applyCoupon");
  }

  async removeCoupon(principal: Principal): Promise<CartView> {
    // TODO(phase-3 M4): clear the cart's coupon and return the view.
    throw new Error("TODO(phase-3 M4): CartService.removeCoupon");
  }
}
