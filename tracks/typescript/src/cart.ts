// The cart: one per signed-in user, stored in the database, priced live with current prices.
import type { Clock } from "./clock.js";
import { insufficientStock, notFound, validationError } from "./errors.js";
import type { CartView, Principal } from "./models.js";
import { priceCart } from "./money.js";
import type { CartRepository, ProductRepository } from "./repository.js";
import * as v from "./validation.js";

export const MAX_LINES = 20;

export class CartService {
  constructor(
    readonly carts: CartRepository,
    private readonly products: ProductRepository,
    private readonly clock: Clock,
  ) {}

  async view(principal: Principal): Promise<CartView> {
    const state = await this.carts.cart(principal.userId);
    const coupon = state.couponCode ? await this.carts.findCoupon(state.couponCode) : null;
    const pricing = priceCart(
      state.lines.map((l) => [l.product.pricePaise, l.quantity]),
      coupon,
    );
    return { ...state, pricing };
  }

  async setItem(principal: Principal, sku: string, payload: unknown): Promise<CartView> {
    const qty = v.quantity(v.requireObject(payload).quantity);
    const product = await this.products.findProduct(sku.trim().toUpperCase());
    if (!product || !product.active) throw notFound("product");
    if (qty > product.stock) throw insufficientStock(`only ${product.stock} left of ${product.sku}`);
    const { lines } = await this.carts.cart(principal.userId);
    if (lines.length >= MAX_LINES && lines.every((l) => l.product.id !== product.id)) {
      throw validationError(`a cart holds at most ${MAX_LINES} different products`);
    }
    await this.carts.setCartItem(principal.userId, product.id, qty, this.clock());
    return this.view(principal);
  }

  async removeItem(principal: Principal, sku: string): Promise<CartView> {
    const product = await this.products.findProduct(sku.trim().toUpperCase());
    if (!product) throw notFound("product");
    await this.carts.removeCartItem(principal.userId, product.id); // idempotent: removing twice is fine
    return this.view(principal);
  }

  async applyCoupon(principal: Principal, payload: unknown): Promise<CartView> {
    const code = v.couponCode(v.requireObject(payload).code);
    const coupon = await this.carts.findCoupon(code);
    if (!coupon || !coupon.active) throw notFound("coupon");
    await this.carts.setCoupon(principal.userId, code, this.clock());
    return this.view(principal);
  }

  async removeCoupon(principal: Principal): Promise<CartView> {
    await this.carts.setCoupon(principal.userId, null, this.clock());
    return this.view(principal);
  }
}
