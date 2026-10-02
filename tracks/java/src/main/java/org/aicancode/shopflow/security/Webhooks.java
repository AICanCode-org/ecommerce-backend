package org.aicancode.shopflow.security;

import java.security.SecureRandom;
import java.util.HexFormat;

/** Webhook signatures (HMAC-SHA256 of the RAW body) and payment ids. */
public final class Webhooks {

    public static final String SIGNATURE_PREFIX = "sha256=";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Webhooks() {
    }

    /** The value of the X-Signature header: "sha256=" + hex HMAC-SHA256 of the RAW request body. */
    public static String sign(String secret, byte[] body) {
        // TODO(phase-3 M2): the X-Signature value: 'sha256=' + hex HMAC-SHA256 of the RAW request body with
        //   the webhook secret.
        throw new UnsupportedOperationException("TODO(phase-3 M2): Webhooks.sign");
    }

    /** Constant-time comparison: a byte-by-byte equals() leaks how many leading characters were right. */
    public static boolean verify(String secret, byte[] body, String header) {
        // TODO(phase-3 M2): missing header or no 'sha256=' prefix -> false; otherwise compare with sign() in
        //   CONSTANT time (MessageDigest.isEqual, never equals).
        throw new UnsupportedOperationException("TODO(phase-3 M2): Webhooks.verify");
    }

    public static String newPaymentId() {
        byte[] buf = new byte[12];
        RANDOM.nextBytes(buf);
        return "pay_" + HexFormat.of().formatHex(buf);
    }
}
