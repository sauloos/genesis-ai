package ai.genesisbrands.security;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignedTokensTest {

    private final SignedTokens tokens = new SignedTokens("a-long-enough-test-secret");
    private final Instant now = Instant.parse("2026-11-28T10:00:00Z");

    @Test
    void aFreshTokenVerifiesToItsPayload() {
        String t = tokens.sign("u1|e1|EVOLVED", now.plusSeconds(60));
        assertThat(tokens.verify(t, now)).contains("u1|e1|EVOLVED");
    }

    @Test
    void anExpiredTokenIsRejected() {
        String t = tokens.sign("u1", now.plusSeconds(60));
        assertThat(tokens.verify(t, now.plusSeconds(61))).isEmpty();
    }

    @Test
    void aTamperedPayloadOrExpiryIsRejected() {
        String t = tokens.sign("u1", now.plusSeconds(60));
        String[] p = t.split("\\.");
        String otherPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("u2".getBytes());
        assertThat(tokens.verify(otherPayload + "." + p[1] + "." + p[2], now)).isEmpty();
        assertThat(tokens.verify(p[0] + "." + (Long.parseLong(p[1]) + 9999) + "." + p[2], now)).isEmpty();
    }

    @Test
    void aTokenFromAnotherKeyOrGarbageIsRejected() {
        String t = new SignedTokens("a-different-long-secret").sign("u1", now.plusSeconds(60));
        assertThat(tokens.verify(t, now)).isEmpty();
        assertThat(tokens.verify("garbage", now)).isEmpty();
        assertThat(tokens.verify(null, now)).isEmpty();
        assertThat(tokens.verify("a.b.c", now)).isEmpty();
    }

    @Test
    void withoutAKeyNothingIsSignedOrAccepted() {
        SignedTokens none = new SignedTokens("short");
        assertThat(none.ready()).isFalse();
        assertThatThrownBy(() -> none.sign("u1", now.plusSeconds(60))).isInstanceOf(IllegalStateException.class);
        assertThat(none.verify(tokens.sign("u1", now.plusSeconds(60)), now)).isEmpty();
    }
}
