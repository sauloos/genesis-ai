package ai.genesisbrands.publishing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * The one secret publishing needs, {@code genesis.publishing.token-key} (32 random bytes,
 * base64; keep it in a secret store). It encrypts platform tokens at rest (AES-GCM) and signs
 * the short-lived values that cross the browser or a platform: the OAuth {@code state} and
 * the public media URLs. Without it publishing stays off.
 */
@Component
public class TokenVault {

    private static final String PREFIX = "v1:";
    private static final int IV_BYTES = 12;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec cipherKey;
    private final SecretKeySpec signKey;

    public TokenVault(@Value("${genesis.publishing.token-key:}") String key) {
        byte[] raw = decodeKey(key);
        if (raw == null) {
            cipherKey = null;
            signKey = null;
        } else {
            cipherKey = new SecretKeySpec(raw, "AES");
            signKey = new SecretKeySpec(sha256(("sign:" + Base64.getEncoder().encodeToString(raw)).getBytes(StandardCharsets.UTF_8)), "HmacSHA256");
        }
    }

    private static byte[] decodeKey(String key) {
        if (key == null || key.isBlank()) return null;
        try {
            byte[] raw = Base64.getDecoder().decode(key.trim());
            return raw.length == 32 ? raw : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public boolean ready() {
        return cipherKey != null;
    }

    public String encrypt(String plain) {
        if (plain == null) return null;
        requireReady();
        try {
            byte[] iv = new byte[IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, cipherKey, new GCMParameterSpec(128, iv));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the token", e);
        }
    }

    public String decrypt(String sealed) {
        if (sealed == null) return null;
        requireReady();
        if (!sealed.startsWith(PREFIX)) throw new IllegalStateException("Unknown token format");
        try {
            byte[] all = Base64.getDecoder().decode(sealed.substring(PREFIX.length()));
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, cipherKey, new GCMParameterSpec(128, all, 0, IV_BYTES));
            return new String(c.doFinal(all, IV_BYTES, all.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not decrypt the token (was the key changed?)", e);
        }
    }

    /** A URL-safe token carrying {@code payload} until {@code expires}; only this server can mint or read it. */
    public String sign(String payload, Instant expires) {
        requireReady();
        String body = expires.getEpochSecond() + "\n" + payload;
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString(body.getBytes(StandardCharsets.UTF_8)) + "." + b64.encodeToString(mac(body));
    }

    /** The payload of a token this server signed, if it is intact and not expired. */
    public Optional<String> verify(String token, Instant now) {
        if (!ready() || token == null) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0) return Optional.empty();
        try {
            Base64.Decoder b64 = Base64.getUrlDecoder();
            String body = new String(b64.decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            if (!MessageDigest.isEqual(mac(body), b64.decode(token.substring(dot + 1)))) return Optional.empty();
            int nl = body.indexOf('\n');
            if (nl <= 0 || Long.parseLong(body.substring(0, nl)) < now.getEpochSecond()) return Optional.empty();
            return Optional.of(body.substring(nl + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] mac(String body) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(signKey);
            return m.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private void requireReady() {
        if (!ready()) throw new IllegalStateException("genesis.publishing.token-key is not set");
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
