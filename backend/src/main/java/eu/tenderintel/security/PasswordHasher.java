package eu.tenderintel.security;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** Argon2id hashing (OWASP-recommended). Also used to equalise timing for unknown accounts during login. */
public class PasswordHasher {

    private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    private final String dummyHash = encoder.encode("timing-equalisation-dummy-password");

    public String hash(String raw) {
        return encoder.encode(raw);
    }

    public boolean matches(String raw, String hash) {
        return encoder.matches(raw, hash);
    }

    /** Spend the same time as a real verification when the account does not exist. */
    public void burnTime(String raw) {
        encoder.matches(raw, dummyHash);
    }
}
