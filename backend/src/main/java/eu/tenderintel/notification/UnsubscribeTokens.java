package eu.tenderintel.notification;

import eu.tenderintel.config.AppProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Signed links that switch off notification e-mails without signing in (required for one-click unsubscribe).
 * The token names the person and the organization; it is an HMAC with a key derived from the server secret, so it
 * cannot be forged or changed to another account. It does not expire: an unsubscribe link must keep working.
 */
@Component
public class UnsubscribeTokens {

    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();
    private final byte[] key;

    UnsubscribeTokens(AppProperties props) {
        this.key = hmac(Base64.getDecoder().decode(props.security().jwtSecret()),
                "notification-unsubscribe-v1".getBytes(StandardCharsets.UTF_8));
    }

    public String create(UUID userId, UUID orgId) {
        String body = userId + "." + orgId;
        return ENC.encodeToString(body.getBytes(StandardCharsets.UTF_8)) + "."
                + ENC.encodeToString(hmac(key, body.getBytes(StandardCharsets.UTF_8)));
    }

    /** {@code [userId, orgId]}, or null when the token is malformed or not signed by this server. */
    public UUID[] verify(String token) {
        if (token == null || token.length() > 300) {
            return null;
        }
        int dot = token.indexOf('.');
        if (dot < 1) {
            return null;
        }
        try {
            byte[] body = DEC.decode(token.substring(0, dot));
            byte[] sig = DEC.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(sig, hmac(key, body))) {
                return null;
            }
            String[] parts = new String(body, StandardCharsets.UTF_8).split("\\.");
            return parts.length == 2 ? new UUID[] {UUID.fromString(parts[0]), UUID.fromString(parts[1])} : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
