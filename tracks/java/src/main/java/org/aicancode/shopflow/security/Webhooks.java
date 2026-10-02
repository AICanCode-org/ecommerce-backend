package org.aicancode.shopflow.security;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Webhook signatures (HMAC-SHA256 of the RAW body) and payment ids. */
public final class Webhooks {

    public static final String SIGNATURE_PREFIX = "sha256=";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Webhooks() {
    }

    /** The value of the X-Signature header: "sha256=" + hex HMAC-SHA256 of the RAW request body. */
    public static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return SIGNATURE_PREFIX + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison: a byte-by-byte equals() leaks how many leading characters were right. */
    public static boolean verify(String secret, byte[] body, String header) {
        if (header == null || !header.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }
        byte[] expected = sign(secret, body).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, header.strip().getBytes(StandardCharsets.UTF_8));
    }

    public static String newPaymentId() {
        byte[] buf = new byte[12];
        RANDOM.nextBytes(buf);
        return "pay_" + HexFormat.of().formatHex(buf);
    }
}
