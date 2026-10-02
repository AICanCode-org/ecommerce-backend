package org.aicancode.shopflow.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.aicancode.shopflow.domain.Models.Principal;
import org.aicancode.shopflow.domain.Models.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Access tokens (JWT, HS256). One hour, no refresh tokens: log in again (see spec/requirements.md). */
public class TokenService {

    public static final String ISSUER = "shopflow";
    public static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(60);
    public static final int MIN_SECRET_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Logger LOG = LoggerFactory.getLogger(TokenService.class);

    private final byte[] secret;
    private final Clock clock;

    public TokenService(String secret, Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    /** Unset means a random per-process secret (dev only); shorter than 32 bytes is an error. */
    public static String resolveSecret(String configured, String name) {
        if (configured == null || configured.isEmpty()) {
            LOG.warn("{} is not set: using a random secret for this process (dev only)", name);
            return randomToken(48);
        }
        if (configured.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException(name + " must be at least " + MIN_SECRET_BYTES + " bytes");
        }
        return configured;
    }

    public static String resolveSecret(String configured) {
        return resolveSecret(configured, "JWT_SECRET");
    }

    public String issueAccessToken(User user) {
        // TODO(phase-3 M2): HS256 JWT with sub (user id as a string), role, iss=ISSUER, iat and exp = now +
        //   ACCESS_TOKEN_TTL (1 hour), using the injected clock.
        throw new UnsupportedOperationException("TODO(phase-3 M2): TokenService.issueAccessToken");
    }

    public Principal verifyAccessToken(String token) {
        // TODO(phase-3 M2): pin the algorithm to HS256 (reject alg=none / RS256), check the issuer, check exp
        //   against the injected CLOCK, sub must be ASCII digits and role a known role. Anything wrong -> 401
        //   unauthorized 'invalid or expired token'.
        throw new UnsupportedOperationException("TODO(phase-3 M2): TokenService.verifyAccessToken");
    }

    public static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
