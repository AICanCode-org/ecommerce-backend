package org.aicancode.shopflow.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import org.aicancode.shopflow.domain.ApiException;
import org.aicancode.shopflow.domain.DuplicateException;
import org.aicancode.shopflow.domain.Models.LoginResult;
import org.aicancode.shopflow.domain.Models.Principal;
import org.aicancode.shopflow.domain.Models.User;
import org.aicancode.shopflow.domain.Times;
import org.aicancode.shopflow.domain.Validation;
import org.aicancode.shopflow.repo.UserRepository;
import org.aicancode.shopflow.security.PasswordHasher;
import org.aicancode.shopflow.security.TokenService;

/** Accounts, login and roles. Framework-free: the HTTP layer only parses and formats. */
public class AuthService {

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
        if (!Arrays.asList(roles).contains(principal.role())) {
            throw ApiException.forbidden("requires role " + String.join(" or ", roles));
        }
    }

    public User register(Object payload) {
        Map<String, Object> data = Validation.requireObject(payload);
        String email = Validation.email(data.get("email"));
        String password = Validation.password(data.get("password"));
        String name = Validation.displayName(data.get("displayName"));
        // Never read "role" from the request: everyone starts as CUSTOMER (mass-assignment defence).
        try {
            return users.insertUser(email, hasher.hash(password), name, "CUSTOMER", Times.now(clock));
        } catch (DuplicateException e) {
            throw ApiException.emailTaken();
        }
    }

    public LoginResult login(Object payload) {
        Map<String, Object> data = Validation.requireObject(payload);
        if (!(data.get("email") instanceof String email) || !(data.get("password") instanceof String password)) {
            throw ApiException.validation("email and password are required");
        }
        User user = users.findUserByEmail(email.strip().toLowerCase(Locale.ROOT)).orElse(null);
        // verify() also runs for unknown e-mails (against a dummy hash): same answer, same timing.
        boolean ok = password.getBytes(StandardCharsets.UTF_8).length <= 72
                && hasher.verify(password, user == null ? null : user.passwordHash());
        if (!ok || user == null) {
            throw ApiException.invalidCredentials();
        }
        return new LoginResult(tokens.issueAccessToken(user), user);
    }

    public User me(Principal principal) {
        return users.findUser(principal.userId())
                .orElseThrow(() -> ApiException.unauthorized("account no longer exists"));
    }

    /** Start-up bootstrap from ADMIN_EMAIL / ADMIN_PASSWORD (there is no other way to get an ADMIN). */
    public User ensureAdmin(String rawEmail, String password) {
        String email = Validation.email(rawEmail);
        User existing = users.findUserByEmail(email).orElse(null);
        if (existing != null) {
            return "ADMIN".equals(existing.role()) ? existing : users.updateRole(existing.id(), "ADMIN").orElseThrow();
        }
        return users.insertUser(email, hasher.hash(Validation.password(password)), "Admin", "ADMIN", Times.now(clock));
    }

    public Principal authenticate(String authorization) {
        Principal principal = optional(authorization);
        if (principal == null) {
            throw ApiException.unauthorized();
        }
        return principal;
    }

    /** No header -> anonymous. A header that is present but wrong is always a 401, never anonymous. */
    public Principal optional(String authorization) {
        if (authorization == null) {
            return null;
        }
        int space = authorization.indexOf(' ');
        String scheme = space < 0 ? authorization : authorization.substring(0, space);
        String token = space < 0 ? "" : authorization.substring(space + 1).strip();
        if (!"bearer".equals(scheme.toLowerCase(Locale.ROOT)) || token.isEmpty()) {
            throw ApiException.unauthorized("use Authorization: Bearer <token>");
        }
        return tokens.verifyAccessToken(token);
    }
}
