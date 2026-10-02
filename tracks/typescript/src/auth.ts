// Accounts, login and roles. Framework-free: the HTTP layer only parses and formats.
import type { Clock } from "./clock.js";
import type { LoginResult, Principal, Role, User } from "./models.js";
import type { UserRepository } from "./repository.js";
import type { PasswordHasher, TokenService } from "./security.js";

export function requireRole(principal: Principal, ...roles: Role[]): void {
  // TODO(phase-3 M2): 403 forbidden 'requires role X' unless principal.role is one of the given roles.
  throw new Error("TODO(phase-3 M2): requireRole");
}

export class AuthService {
  constructor(
    readonly users: UserRepository,
    private readonly hasher: PasswordHasher,
    private readonly tokens: TokenService,
    private readonly clock: Clock,
  ) {}

  async register(payload: unknown): Promise<User> {
    // TODO(phase-3 M2): validate email, password, displayName; ALWAYS create a CUSTOMER (ignore any 'role' in
    //   the payload: mass assignment!); hash the password; DuplicateError -> 409 email-taken.
    throw new Error("TODO(phase-3 M2): AuthService.register");
  }

  async login(payload: unknown): Promise<LoginResult> {
    // TODO(phase-3 M2): email and password must be strings (400); the SAME 401 invalid-credentials for an
    //   unknown e-mail and a wrong password, and still run one bcrypt verify for an unknown e-mail (timing).
    //   Passwords over 72 bytes are a 401 too. Returns the access token + the user.
    throw new Error("TODO(phase-3 M2): AuthService.login");
  }

  async me(principal: Principal): Promise<User> {
    // TODO(phase-3 M2): the current user; 401 unauthorized 'account no longer exists' if it was removed.
    throw new Error("TODO(phase-3 M2): AuthService.me");
  }

  /** Start-up bootstrap from ADMIN_EMAIL / ADMIN_PASSWORD (there is no other way to get an ADMIN). */
  async ensureAdmin(rawEmail: string, password: string): Promise<User> {
    // TODO(phase-3 M2): create ADMIN_EMAIL as an ADMIN, or promote it if it exists. Until you build it the
    //   app starts without an admin.
    console.warn("TODO(phase-3 M2): ensureAdmin - starting without an admin");
    return null as never;
  }

  async authenticate(authorization: string | undefined): Promise<Principal> {
    // TODO(phase-3 M2): optional(header), but null -> 401 unauthorized (the HTTP layer adds WWW-
    //   Authenticate).
    throw new Error("TODO(phase-3 M2): AuthService.authenticate");
  }

  /** No header -> anonymous. A header that is present but wrong is always a 401, never anonymous. */
  async optional(authorization: string | undefined): Promise<Principal | null> {
    // TODO(phase-3 M2): no header -> null (anonymous). 'Bearer <jwt>' -> verify it. A header that is present
    //   but bad is ALWAYS a 401, never silently anonymous.
    throw new Error("TODO(phase-3 M2): AuthService.optional");
  }
}
