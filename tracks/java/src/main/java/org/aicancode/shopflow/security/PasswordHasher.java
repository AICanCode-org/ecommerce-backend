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
        // TODO(phase-3 M2): bcrypt with the configured cost (4 in tests, 10 in production).
        throw new UnsupportedOperationException("TODO(phase-3 M2): PasswordHasher.hash");
    }

    public boolean verify(String password, String passwordHash) {
        // TODO(phase-3 M2): bcrypt check; when password_hash is null (unknown e-mail) verify against the
        //   dummy hash anyway and return false: same timing either way. A malformed hash -> false, never an
        //   exception.
        throw new UnsupportedOperationException("TODO(phase-3 M2): PasswordHasher.verify");
    }
}
