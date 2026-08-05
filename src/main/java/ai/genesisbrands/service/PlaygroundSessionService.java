package ai.genesisbrands.service;

import ai.genesisbrands.model.PlaygroundSession;
import ai.genesisbrands.repository.PlaygroundSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PlaygroundSessionService {

    private final PlaygroundSessionRepository repo;
    private final ObjectMapper objectMapper;

    public List<PlaygroundSession> list(String agentId) {
        return repo.findAllByAgentIdOrderByCreatedAtDesc(agentId);
    }

    public PlaygroundSession get(String id) {
        return repo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Playground session not found: " + id));
    }

    public PlaygroundSession save(String agentId, String method, boolean compare, boolean evaluate,
                                   JsonNode brief, JsonNode result) {
        PlaygroundSession session = new PlaygroundSession();
        session.setId(UUID.randomUUID().toString());
        session.setAgentId(agentId);
        session.setMethod(method);
        session.setCompareMode(compare);
        session.setEvaluateMode(evaluate);
        session.setLabel(deriveLabel(brief));
        session.setBriefJson(brief.toString());
        session.setResultJson(result.toString());
        return repo.save(session);
    }

    public void delete(String id) {
        repo.deleteById(id);
    }

    public JsonNode briefOf(PlaygroundSession session) {
        return parse(session.getBriefJson());
    }

    public JsonNode resultOf(PlaygroundSession session) {
        return parse(session.getResultJson());
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("Corrupt playground session JSON: " + e.getMessage(), e);
        }
    }

    private String deriveLabel(JsonNode brief) {
        String name = brief.path("brand").path("name").asText(null);
        String direction = brief.path("direction").asText(null);
        if (name == null && direction == null) return "Playground run";
        if (direction == null) return name;
        String titled = direction.substring(0, 1).toUpperCase() + direction.substring(1).toLowerCase();
        return name != null ? name + " — " + titled : titled;
    }
}
