package ai.genesisbrands.previews;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewRateLimiterTest {

    @Test
    void allowsUpToTheLimitPerWindowThenRecovers() {
        AtomicLong now = new AtomicLong(1_000_000);
        PreviewRateLimiter l = new PreviewRateLimiter(now::get);
        Duration minute = Duration.ofMinutes(1);
        assertThat(l.allow("k", 2, minute)).isTrue();
        assertThat(l.allow("k", 2, minute)).isTrue();
        assertThat(l.allow("k", 2, minute)).isFalse();
        assertThat(l.allow("other", 2, minute)).isTrue();   // keys are independent
        now.addAndGet(61_000);
        assertThat(l.allow("k", 2, minute)).isTrue();
    }

    @Test
    void sweepForgetsIdleKeys() {
        AtomicLong now = new AtomicLong(1_000_000);
        PreviewRateLimiter l = new PreviewRateLimiter(now::get);
        l.allow("k", 1, Duration.ofMinutes(1));
        now.addAndGet(Duration.ofDays(2).toMillis());
        l.sweep(Duration.ofDays(1));
        assertThat(l.allow("k", 1, Duration.ofMinutes(1))).isTrue();
    }
}
