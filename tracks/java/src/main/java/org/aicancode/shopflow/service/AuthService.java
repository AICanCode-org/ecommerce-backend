package org.aicancode.shopflow.service;

import java.time.Clock;
import org.aicancode.shopflow.domain.Models.LoginResult;
import org.aicancode.shopflow.domain.Models.Principal;
import org.aicancode.shopflow.domain.Models.User;
import org.aicancode.shopflow.repo.UserRepository;
import org.aicancode.shopflow.security.PasswordHasher;
import org.aicancode.shopflow.security.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Accounts, login and roles. Framework-free: the HTTP layer only parses and formats. */
public class AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthService.class);
    private final UserRepository users;
    private final PasswordHasher hasher;
    private final TokenService tokens;
    private final Clock clock;

    public AuthService(UserRepository users, PasswordHasher hasher, TokenService tokens, Clock clock) {
        this.users = users;
        this.hasher = hasher;
        this.tokens = tokens;
        this.clock = clock;
    }

    public UserRepository users() {
        return users;
    }

    public static void requireRole(Principal principal, String... roles) {
        // TODO(phase-3 M2): 403 forbidden 'requires role X' unless principal.role is one of the given roles.
        throw new UnsupportedOperationException("TODO(phase-3 M2): AuthService.requireRole");
    }

    public User register(Object payload) {
        // TODO(phase-3 M2): validate email, password, displayName; ALWAYS create a CUSTOMER (ignore any
        //   'role' in the payload: mass assignment!); hash the password; DuplicateError -> 409 email-taken.
        throw new UnsupportedOperationException("TODO(phase-3 M2): AuthService.register");
    }

    public LoginResult login(Object payload) {
        // TODO(phase-3 M2): email and password must be strings (400); the SAME 401 invalid-credentials for an
        //   unknown e-mail and a wrong password, and still run one bcrypt verify for an unknown e-mail
        //   (timing). Passwords over 72 bytes are a 401 too. Returns the access token + the user.
        throw new UnsupportedOperationException("TODO(phase-3 M2): AuthService.login");
    }

    public User me(Principal principal) {
        // TODO(phase-3 M2): the current user; 401 unauthorized 'account no longer exists' if it was removed.
        throw new UnsupportedOperationException("TODO(phase-3 M2): AuthService.me");
    }

    /** Start-up bootstrap from ADMIN_EMAIL / ADMIN_PASSWORD (there is no other way to get an ADMIN). */
    public User ensureAdmin(String rawEmail, String password) {
        // TODO(phase-3 M2): create ADMIN_EMAIL as an ADMIN, or promote it if it exists. Until you build it
        //   the app starts without an admin.
        LOG.warn("TODO(phase-3 M2): ensureAdmin - starting without an admin");
        return null;
    }

    public Principal authenticate(String authorization) {
        // TODO(phase-3 M2): optional(header), but null -> 401 unauthorized (the HTTP layer adds WWW-
        //   Authenticate).
        throw new UnsupportedOperationException("TODO(phase-3 M2): AuthService.authenticate");
    }

    /** No header -> anonymous. A header that is present but wrong is always a 401, never anonymous. */
    public Principal optional(String authorization) {
        // TODO(phase-3 M2): no header -> null (anonymous). 'Bearer <jwt>' -> verify it. A header that is
        //   present but bad is ALWAYS a 401, never silently anonymous.
        throw new UnsupportedOperationException("TODO(phase-3 M2): AuthService.optional");
    }
}
