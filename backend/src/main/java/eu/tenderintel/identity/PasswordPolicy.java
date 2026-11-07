package eu.tenderintel.identity;

import eu.tenderintel.common.error.ApiException;
import java.util.Locale;

/**
 * NIST SP 800-63B style policy: long passwords, no arbitrary composition rules. Breached-password screening
 * (k-anonymity API) can be added later behind this class.
 */
final class PasswordPolicy {

    static final int MIN_LENGTH = 12;
    static final int MAX_LENGTH = 128;

    private PasswordPolicy() {
    }

    static void validate(String password, String email) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw ApiException.badRequest("weak_password",
                    "Password must be between " + MIN_LENGTH + " and " + MAX_LENGTH + " characters");
        }
        if (password.chars().distinct().count() < 4) {
            throw ApiException.badRequest("weak_password", "Password is too repetitive");
        }
        if (email != null) {
            String local = email.toLowerCase(Locale.ROOT).split("@")[0];
            if (local.length() >= 3 && password.toLowerCase(Locale.ROOT).contains(local)) {
                throw ApiException.badRequest("weak_password", "Password must not contain your e-mail address");
            }
        }
    }
}
