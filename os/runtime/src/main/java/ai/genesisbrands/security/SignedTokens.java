package ai.genesisbrands.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Short-lived, tamper-proof values that cross the browser: {@code payload.expiry.signature}
 * (HMAC-SHA256, URL-safe base64). Verification is constant-time and rejects an expired or
 * altered value. The payload is readable, so it must hold identifiers only, never secrets.
 */
public final class SignedTokens {

    private final SecretKeySpec key;

    /** @param secret any string of at least 16 characters; shorter or blank leaves the signer unready */
    public SignedTokens(String secret) {
        this.key = secret == null || secret.trim().length() < 16 ? null
            : new SecretKeySpec(secret.trim().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    public boolean ready() {
        return key != null;
    }

    public String sign(String payload, Instant expires) {
        requireReady();
        String body = b64(payload.getBytes(StandardCharsets.UTF_8)) + "." + expires.getEpochSecond();
        return body + "." + b64(mac(body));
    }

    /** The payload when the token is genuine and unexpired. */
    public Optional<String> verify(String token, Instant now) {
        if (key == null || token == null) return Optional.empty();
        String[] parts = token.split("\\.");
        if (parts.length != 3) return Optional.empty();
        String body = parts[0] + "." + parts[1];
        try {
            byte[] given = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(given, mac(body))) return Optional.empty();
            if (Long.parseLong(parts[1]) <= now.getEpochSecond()) return Optional.empty();
            return Optional.of(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] mac(String body) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(key);
            return m.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not sign", e);
        }
    }

    private void requireReady() {
        if (key == null) throw new IllegalStateException("No signing key is configured");
    }

    private static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
