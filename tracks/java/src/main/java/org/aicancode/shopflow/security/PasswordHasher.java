package org.aicancode.shopflow.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** bcrypt with a configurable cost (10 in production, 4 in tests to keep them fast). */
public class PasswordHasher {

    private final BCryptPasswordEncoder encoder;
    /** Used when the e-mail is unknown, so a login takes the same time either way. */
    private final String dummy;

    public PasswordHasher(int cost) {
        if (cost < 4 || cost > 15) {
            throw new IllegalArgumentException("BCRYPT_COST must be 4-15");
        }
        this.encoder = new BCryptPasswordEncoder(cost);
        this.dummy = encoder.encode("not-a-real-password");
    }

    public String hash(String password) {
        return encoder.encode(password);
    }

    public boolean verify(String password, String passwordHash) {
        try {
            boolean ok = encoder.matches(password, passwordHash == null ? dummy : passwordHash);
            return ok && passwordHash != null;
        } catch (IllegalArgumentException e) {
            return false; // a malformed hash never matches
        }
    }
}
