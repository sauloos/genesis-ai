package ai.genesisbrands.standing;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StandingPublishingTest {

    @Test
    void postTextAppendsHashtagsMarkingBareOnes() {
        assertEquals("Light roasts.\n\n#coffee #lightroast",
            StandingPublishing.postText(Map.of("text", "  Light roasts. ", "hashtags", List.of("#coffee", "lightroast", " "))));
        assertEquals("Just text", StandingPublishing.postText(Map.of("text", "Just text")));
    }

    @Test
    void publishingTimeIsAnIsoInstantOrNow() {
        assertNull(StandingPublishing.parseAt(null));
        assertNull(StandingPublishing.parseAt(" "));
        assertEquals(Instant.parse("2026-11-02T10:00:00Z"), StandingPublishing.parseAt("2026-11-02T10:00:00Z"));
        assertThrows(ResponseStatusException.class, () -> StandingPublishing.parseAt("tomorrow"));
    }
}
