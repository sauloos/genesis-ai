package ai.genesisbrands.publishing;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenVaultTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void notReadyWithoutAValidKey() {
        assertFalse(new TokenVault("").ready());
        assertFalse(new TokenVault("c2hvcnQ=").ready());
        assertTrue(new TokenVault(KEY).ready());
    }

    @Test
    void encryptsAndDecrypts() {
        TokenVault vault = new TokenVault(KEY);
        String sealed = vault.encrypt("secret-token");
        assertTrue(sealed.startsWith("v1:"));
        assertFalse(sealed.contains("secret-token"));
        assertNotEquals(sealed, vault.encrypt("secret-token"));
        assertEquals("secret-token", vault.decrypt(sealed));
    }

    @Test
    void signedTokensVerifyUntilTheyExpire() {
        TokenVault vault = new TokenVault(KEY);
        Instant now = Instant.parse("2026-10-19T10:00:00Z");
        String token = vault.sign("client\nmeta\n/live/dashboard", now.plus(Duration.ofMinutes(15)));
        assertEquals(Optional.of("client\nmeta\n/live/dashboard"), vault.verify(token, now));
        assertTrue(vault.verify(token, now.plus(Duration.ofMinutes(16))).isEmpty());
    }

    @Test
    void tamperedOrForeignTokensAreRejected() {
        TokenVault vault = new TokenVault(KEY);
        Instant now = Instant.now();
        String token = vault.sign("publishing/c/j/0.jpg", now.plusSeconds(60));
        String forged = vault.sign("publishing/other/j/0.jpg", now.plusSeconds(60));
        String spliced = forged.substring(0, forged.indexOf('.')) + token.substring(token.indexOf('.'));
        assertTrue(vault.verify(spliced, now).isEmpty());
        assertTrue(vault.verify("garbage", now).isEmpty());
        TokenVault other = new TokenVault(Base64.getEncoder().encodeToString(new byte[] {
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32}));
        assertTrue(other.verify(token, now).isEmpty());
    }
}
