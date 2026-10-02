package org.aicancode.shopflow.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import org.aicancode.shopflow.domain.ApiException;
import org.aicancode.shopflow.domain.Models;
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
        long now = clock.instant().getEpochSecond();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(Long.toString(user.id()))
                .claim("role", user.role())
                .issuer(ISSUER)
                .issueTime(Date.from(Instant.ofEpochSecond(now)))
                .expirationTime(Date.from(Instant.ofEpochSecond(now + ACCESS_TOKEN_TTL.toSeconds())))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).type(JOSEObjectType.JWT).build(), claims);
        try {
            jwt.sign(new MACSigner(secret));
        } catch (JOSEException e) {
            throw new IllegalStateException("cannot sign token", e);
        }
        return jwt.serialize();
    }

    public Principal verifyAccessToken(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            // Pin the algorithm: "alg": "none" (not even a SignedJWT) or RS256 tokens are rejected.
            if (!JWSAlgorithm.HS256.equals(jwt.getHeader().getAlgorithm()) || !jwt.verify(new MACVerifier(secret))) {
                throw ApiException.unauthorized("invalid or expired token");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date exp = claims.getExpirationTime();
            String sub = claims.getSubject();
            Object role = claims.getClaim("role");
            // Expiry is checked against the injected clock, so tests can travel in time.
            if (!ISSUER.equals(claims.getIssuer()) || exp == null || !exp.toInstant().isAfter(clock.instant())) {
                throw ApiException.unauthorized("invalid or expired token");
            }
            if (sub == null || !sub.matches("[0-9]{1,18}") || !(role instanceof String r) || !Models.ROLES.contains(r)) {
                throw ApiException.unauthorized("invalid or expired token");
            }
            return new Principal(Long.parseLong(sub), r);
        } catch (ParseException | JOSEException | ClassCastException e) {
            throw ApiException.unauthorized("invalid or expired token");
        }
    }

    public static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
