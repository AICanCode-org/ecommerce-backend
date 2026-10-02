// Accounts, login and roles. Framework-free: the HTTP layer only parses and formats.
import type { Clock } from "./clock.js";
import {
  DuplicateError,
  emailTaken,
  forbidden,
  invalidCredentials,
  unauthorized,
  validationError,
} from "./errors.js";
import type { LoginResult, Principal, Role, User } from "./models.js";
import type { UserRepository } from "./repository.js";
import type { PasswordHasher, TokenService } from "./security.js";
import * as v from "./validation.js";

export function requireRole(principal: Principal, ...roles: Role[]): void {
  if (!roles.includes(principal.role)) throw forbidden(`requires role ${roles.join(" or ")}`);
}

export class AuthService {
  constructor(
    readonly users: UserRepository,
    private readonly hasher: PasswordHasher,
    private readonly tokens: TokenService,
    private readonly clock: Clock,
  ) {}

  async register(payload: unknown): Promise<User> {
    const data = v.requireObject(payload);
    const email = v.email(data.email);
    const password = v.password(data.password);
    const name = v.displayName(data.displayName);
    // Never read "role" from the request: everyone starts as CUSTOMER (mass-assignment defence).
    try {
      return await this.users.insertUser(
        email,
        await this.hasher.hash(password),
        name,
        "CUSTOMER",
        this.clock(),
      );
    } catch (err) {
      if (err instanceof DuplicateError) throw emailTaken();
      throw err;
    }
  }

  async login(payload: unknown): Promise<LoginResult> {
    const data = v.requireObject(payload);
    const { email, password } = data;
    if (typeof email !== "string" || typeof password !== "string") {
      throw validationError("email and password are required");
    }
    const user = await this.users.findUserByEmail(email.trim().toLowerCase());
    // verify() also runs for unknown e-mails (against a dummy hash): same answer, same timing.
    const ok =
      Buffer.byteLength(password, "utf8") <= 72 &&
      (await this.hasher.verify(password, user ? user.passwordHash : null));
    if (!ok || !user) throw invalidCredentials();
    return { accessToken: await this.tokens.issueAccessToken(user), user };
  }

  async me(principal: Principal): Promise<User> {
    const user = await this.users.findUser(principal.userId);
    if (!user) throw unauthorized("account no longer exists");
    return user;
  }

  /** Start-up bootstrap from ADMIN_EMAIL / ADMIN_PASSWORD (there is no other way to get an ADMIN). */
  async ensureAdmin(rawEmail: string, password: string): Promise<User> {
    const email = v.email(rawEmail);
    const existing = await this.users.findUserByEmail(email);
    if (existing)
      return existing.role === "ADMIN" ? existing : (await this.users.updateRole(existing.id, "ADMIN"))!;
    return this.users.insertUser(
      email,
      await this.hasher.hash(v.password(password)),
      "Admin",
      "ADMIN",
      this.clock(),
    );
  }

  async authenticate(authorization: string | undefined): Promise<Principal> {
    const principal = await this.optional(authorization);
    if (!principal) throw unauthorized();
    return principal;
  }

  /** No header -> anonymous. A header that is present but wrong is always a 401, never anonymous. */
  async optional(authorization: string | undefined): Promise<Principal | null> {
    if (authorization === undefined) return null;
    const space = authorization.indexOf(" ");
    const scheme = space < 0 ? authorization : authorization.slice(0, space);
    const token = space < 0 ? "" : authorization.slice(space + 1).trim();
    if (scheme.toLowerCase() !== "bearer" || !token) throw unauthorized("use Authorization: Bearer <token>");
    return this.tokens.verifyAccessToken(token);
  }
}
