package ai.genesisbrands.controller;

import ai.genesisbrands.model.PlaygroundSession;
import ai.genesisbrands.service.PlaygroundSessionService;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/playground")
@RequiredArgsConstructor
@Tag(name = "Playground", description = "Playground run history — persisted agent runs for replay and feedback capture")
public class PlaygroundController {

    private final PlaygroundSessionService playgroundService;

    @GetMapping("/sessions")
    @Operation(summary = "List playground run history for an agent")
    public List<PlaygroundSessionSummary> listSessions(@RequestParam String agentId) {
        return playgroundService.list(agentId).stream()
            .map(s -> PlaygroundSessionSummary.of(s, playgroundService.roundsInfoOf(s)))
            .toList();
    }

    @GetMapping("/sessions/{id}")
    @Operation(summary = "Get a playground run's full brief and result, for replay")
    public PlaygroundSessionDetail getSession(@PathVariable String id) {
        PlaygroundSession session = playgroundService.get(id);
        return new PlaygroundSessionDetail(
            PlaygroundSessionSummary.of(session, playgroundService.roundsInfoOf(session)),
            playgroundService.briefOf(session),
            playgroundService.resultOf(session)
        );
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Persist a completed playground run")
    public PlaygroundSessionSummary saveSession(@RequestBody SaveSessionRequest req) {
        PlaygroundSession session = playgroundService.save(
            req.agentId(), req.method(), req.compare(), req.evaluate(), req.brief(), req.result()
        );
        return PlaygroundSessionSummary.of(session, playgroundService.roundsInfoOf(session));
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a playground run from history")
    public void deleteSession(@PathVariable String id) {
        playgroundService.delete(id);
    }

    public record SaveSessionRequest(
        String agentId, String method, boolean compare, boolean evaluate, JsonNode brief, JsonNode result
    ) {}

    public record PlaygroundSessionSummary(
        String id, String agentId, String method, boolean compareMode, boolean evaluateMode,
        String label, Instant createdAt, Integer rounds, Boolean accepted
    ) {
        static PlaygroundSessionSummary of(PlaygroundSession s, PlaygroundSessionService.RoundsInfo ri) {
            return new PlaygroundSessionSummary(
                s.getId(), s.getAgentId(), s.getMethod(), s.isCompareMode(), s.isEvaluateMode(),
                s.getLabel(), s.getCreatedAt(),
                ri != null ? ri.rounds() : null, ri != null ? ri.accepted() : null
            );
        }
    }

    public record PlaygroundSessionDetail(PlaygroundSessionSummary session, JsonNode brief, JsonNode result) {}
}
