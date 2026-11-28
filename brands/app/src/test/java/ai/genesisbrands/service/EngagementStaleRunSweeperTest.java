package ai.genesisbrands.service;

import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.repository.EngagementRepository;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class EngagementStaleRunSweeperTest {

    private final EngagementRepository repo = mock(EngagementRepository.class);
    private final Instant now = Instant.parse("2026-11-28T12:00:00Z");

    @Test
    void failsOnlyRunsOlderThanTheTimeoutAndCountsWhatItChanged() {
        Instant cutoff = now.minus(Duration.ofMinutes(90));
        when(repo.findStaleRunIds(EngagementStaleRunSweeper.ACTIVE, cutoff)).thenReturn(List.of("a", "b"));
        when(repo.failIfStale(eq("a"), any(), any(), any(), eq(now), eq(cutoff))).thenReturn(1);
        when(repo.failIfStale(eq("b"), any(), any(), any(), eq(now), eq(cutoff))).thenReturn(0); // finished meanwhile
        assertThat(new EngagementStaleRunSweeper(repo, 90).sweep(now)).isEqualTo(1);
        verify(repo).failIfStale("a", Engagement.Status.FAILED, EngagementStaleRunSweeper.ACTIVE, EngagementStaleRunSweeper.MESSAGE, now, cutoff);
    }

    @Test
    void oneFailingRowDoesNotStopTheSweep() {
        Instant cutoff = now.minus(Duration.ofMinutes(10));
        when(repo.findStaleRunIds(EngagementStaleRunSweeper.ACTIVE, cutoff)).thenReturn(List.of("a", "b"));
        when(repo.failIfStale(eq("a"), any(), any(), any(), any(), any())).thenThrow(new RuntimeException("db"));
        when(repo.failIfStale(eq("b"), any(), any(), any(), any(), any())).thenReturn(1);
        assertThat(new EngagementStaleRunSweeper(repo, 10).sweep(now)).isEqualTo(1);
    }

    @Test
    void nothingStaleDoesNothing() {
        when(repo.findStaleRunIds(any(), any())).thenReturn(List.of());
        assertThat(new EngagementStaleRunSweeper(repo, 90).sweep(now)).isZero();
        verify(repo, never()).failIfStale(any(), any(), any(), any(), any(), any());
    }
}
