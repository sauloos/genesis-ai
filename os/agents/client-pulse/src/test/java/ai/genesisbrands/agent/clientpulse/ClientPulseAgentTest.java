package ai.genesisbrands.agent.clientpulse;

import ai.genesisbrands.platform.standing.StandingRunContext;
import ai.genesisbrands.platform.standing.StandingRunResult;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClientPulseAgentTest {

    @Test
    void parsesTitleSummaryAndBody() {
        var r = ClientPulseAgent.Report.parse("TITLE: Two decisions waiting\nSUMMARY: Your logo is ready.\n\nWaiting on you\n- Approve the logo");
        assertEquals("Two decisions waiting", r.title());
        assertEquals("Your logo is ready.", r.summary());
        assertEquals("Waiting on you\n- Approve the logo", r.body());
    }

    @Test
    void replyWithoutHeadersBecomesTheBody() {
        var r = ClientPulseAgent.Report.parse("Just some text\n- point");
        assertNull(r.title());
        assertNull(r.summary());
        assertEquals("Just some text\n- point", r.body());
    }

    @Test
    void runFallsBackToADatedTitleAndPassesFeedbackAndOptions() {
        String[] seen = new String[1];
        var ctx = new StandingRunContext("run", "sub", "client", "Acme", Map.of("language", "Portuguese"), "Brand: Acme",
            List.of(new StandingRunContext.Decision("Last week", "REJECTED", "Too long", Instant.now())),
            null, Instant.parse("2026-10-19T09:00:00Z"), null, java.time.ZoneOffset.UTC, prompt -> { seen[0] = prompt; return "body only"; });
        StandingRunResult result = new ClientPulseAgent().run(ctx);
        var item = result.items().get(0);
        assertEquals("REPORT", item.kind());
        assertEquals("Your account on 19 Oct 2026", item.title());
        assertTrue(seen[0].contains("Write in: Portuguese"));
        assertTrue(seen[0].contains("Too long"));
        assertTrue(seen[0].contains("this is the first"));
    }
}
