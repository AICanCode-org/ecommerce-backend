package org.aicancode.shopflow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.aicancode.shopflow.domain.ApiException;
import org.aicancode.shopflow.domain.Models.Principal;
import org.aicancode.shopflow.domain.Models.User;
import org.aicancode.shopflow.support.MutableClock;
import org.aicancode.shopflow.support.TestApp;
import org.junit.jupiter.api.Test;

/** M2: passwords, access tokens and webhook signatures. */
class SecurityTest {

    private static final User USER = new User(7, "asha@shopflow.test", "x", "Asha", "CUSTOMER", Instant.EPOCH);
    private final MutableClock clock = new MutableClock();
    private final TokenService tokens = new TokenService(TestApp.TEST_SECRET, clock);

    private static void rejected(String token, TokenService tokens) {
        assertThatThrownBy(() -> tokens.verifyAccessToken(token)).isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    assertThat(((ApiException) e).status()).isEqualTo(401);
                    assertThat(((ApiException) e).detail()).isEqualTo("invalid or expired token");
                });
    }

    private static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String sign(JWTClaimsSet claims, String secret) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private static JWTClaimsSet.Builder claims(String sub, String role, String iss) {
        return new JWTClaimsSet.Builder().subject(sub).claim("role", role).issuer(iss);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void saltsBcryptHashesAndVerifiesThem() {
        PasswordHasher hasher = new PasswordHasher(4);
        String a = hasher.hash("password-123");
        assertThat(hasher.hash("password-123")).isNotEqualTo(a);
        assertThat(a).startsWith("$2");
        assertThat(hasher.verify("password-123", a)).isTrue();
        assertThat(hasher.verify("wrong-password", a)).isFalse();
        assertThat(hasher.verify("password-123", null)).isFalse(); // unknown user: still runs bcrypt
        assertThat(hasher.verify("password-123", "not-a-bcrypt-hash")).isFalse();
    }

    @Test
    void boundsTheBcryptCost() {
        assertThatThrownBy(() -> new PasswordHasher(3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PasswordHasher(16)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wantsALongEnoughSecret() {
        assertThat(TokenService.resolveSecret(TestApp.TEST_SECRET)).isEqualTo(TestApp.TEST_SECRET);
        assertThat(TokenService.resolveSecret("")).hasSizeGreaterThanOrEqualTo(32); // random, dev only
        assertThat(TokenService.resolveSecret(null, "PAYMENT_WEBHOOK_SECRET")).hasSizeGreaterThanOrEqualTo(32);
        assertThatThrownBy(() -> TokenService.resolveSecret("too-short")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundTripsAnAccessTokenWithTheRightClaims() throws Exception {
        String token = tokens.issueAccessToken(USER);
        JWTClaimsSet c = SignedJWT.parse(token).getJWTClaimsSet();
        assertThat(List.of(c.getSubject(), c.getStringClaim("role"), c.getIssuer()))
                .containsExactly("7", "CUSTOMER", "shopflow");
        assertThat(Duration.between(c.getIssueTime().toInstant(), c.getExpirationTime().toInstant()).toSeconds())
                .isEqualTo(3600);
        assertThat(tokens.verifyAccessToken(token)).isEqualTo(new Principal(7, "CUSTOMER"));
    }

    @Test
    void rejectsAnExpiredTokenUsingTheInjectedClock() {
        String token = tokens.issueAccessToken(USER);
        clock.advance(Duration.ofMinutes(59));
        tokens.verifyAccessToken(token);
        clock.advance(Duration.ofMinutes(1));
        rejected(token, tokens);
    }

    @Test
    void rejectsForgedOrMalformedTokens() throws Exception {
        Date far = Date.from(Instant.ofEpochSecond(4_000_000_000L));
        List<String> forged = List.of(
                sign(claims("7", "ADMIN", "shopflow").expirationTime(far).build(), "another-secret-that-is-32-bytes-long"),
                sign(claims("7", "ADMIN", "evil").expirationTime(far).build(), TestApp.TEST_SECRET), // wrong issuer
                sign(claims("7", "ROOT", "shopflow").expirationTime(far).build(), TestApp.TEST_SECRET), // unknown role
                sign(claims("x", "ADMIN", "shopflow").expirationTime(far).build(), TestApp.TEST_SECRET), // bad subject
                sign(claims("7", "ADMIN", "shopflow").build(), TestApp.TEST_SECRET), // no expiry
                // "alg": "none" - the classic: an unsigned token that a careless verifier accepts
                b64("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "."
                        + b64("{\"sub\":\"7\",\"role\":\"ADMIN\",\"iss\":\"shopflow\",\"exp\":4000000000}") + ".",
                "not.a.jwt");
        for (String token : forged) {
            rejected(token, tokens);
        }
    }

    @Test
    void signsWebhooksWithHmacSha256OfTheRawBody() {
        byte[] body = bytes("{\"id\":\"evt_1\"}");
        String secret = "s".repeat(32);
        String signature = Webhooks.sign(secret, body);
        assertThat(signature).startsWith("sha256=").hasSize(7 + 64);
        assertThat(Webhooks.verify(secret, body, signature)).isTrue();
        assertThat(Webhooks.verify(secret, bytes("{\"id\":\"evt_1\"} "), signature)).isFalse();
        assertThat(Webhooks.verify("t".repeat(32), body, signature)).isFalse();
        assertThat(Webhooks.verify(secret, body, null)).isFalse();
        assertThat(Webhooks.verify(secret, body, signature.substring(7))).isFalse(); // missing the prefix
    }

    @Test
    void matchesTheKnownSignatureVector() {
        // Same vector in every track: HMAC-SHA256(key="key", "The quick brown fox jumps over the lazy dog")
        assertThat(Webhooks.sign("key", bytes("The quick brown fox jumps over the lazy dog")))
                .isEqualTo("sha256=f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }

    @Test
    void makesRandomPaymentIds() {
        String a = Webhooks.newPaymentId();
        assertThat(Webhooks.newPaymentId()).isNotEqualTo(a);
        assertThat(a).startsWith("pay_").hasSize(28);
    }
}
