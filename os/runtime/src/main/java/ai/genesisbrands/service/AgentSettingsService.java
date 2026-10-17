package ai.genesisbrands.service;

import ai.genesisbrands.agent.config.AgentProperties;
import ai.genesisbrands.agent.config.AgentSettingsOverrideStore;
import ai.genesisbrands.model.AgentSettingsOverride;
import ai.genesisbrands.platform.CoreAgent;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * Admin settings layer over agent config: what the agents admin page shows and edits.
 * <p>
 * Settings are keyed by config key, and one agent can own several keys — its own id plus
 * any {@code <agentId>-…} key such as {@code copy-evaluator} or {@code logo-svg}. A key
 * belongs to the agent with the longest matching id, so a hypothetical {@code brand} agent
 * would never claim {@code brand-book-evaluator}.
 */
@Service
public class AgentSettingsService {

    static final int MAX_TOKENS_LIMIT = 64_000;
    static final int MAX_ITERATIONS_LIMIT = 10;
    static final int RAG_TOP_K_LIMIT = 50;

    private final List<CoreAgent> agents;
    private final AgentProperties agentProperties;
    private final AgentSettingsOverrideStore store;

    public AgentSettingsService(List<CoreAgent> agents, AgentProperties agentProperties,
                                AgentSettingsOverrideStore store) {
        this.agents = agents;
        this.agentProperties = agentProperties;
        this.store = store;
    }

    public List<SettingsGroup> list(String agentId) {
        requireAgent(agentId);
        return agentProperties.getAgents().keySet().stream()
            .filter(key -> agentId.equals(ownerOf(key)))
            .sorted(Comparator.comparing((String key) -> !key.equals(agentId)).thenComparing(key -> key))
            .map(this::group)
            .toList();
    }

    public SettingsGroup update(String agentId, String key, Settings body) {
        requireKey(agentId, key);
        Settings clean = validate(body);
        if (clean.isEmpty()) {
            store.delete(key);
            return group(key);
        }
        AgentSettingsOverride o = store.find(key).orElseGet(AgentSettingsOverride::new);
        o.setConfigKey(key);
        o.setModel(clean.model());
        o.setMaxTokens(clean.maxTokens());
        o.setMaxIterations(clean.maxIterations());
        o.setRagEnabled(clean.ragEnabled());
        o.setRagTopK(clean.ragTopK());
        o.setSystemPrompt(clean.systemPrompt());
        store.save(o);
        return group(key);
    }

    public void reset(String agentId, String key) {
        requireKey(agentId, key);
        store.delete(key);
    }

    private SettingsGroup group(String key) {
        AgentProperties.AgentConfig d = agentProperties.defaults(key);
        String defaultPrompt;
        try {
            defaultPrompt = agentProperties.defaultSystemPrompt(key);
        } catch (IllegalStateException e) {
            defaultPrompt = null;
        }
        Settings defaults = new Settings(d.getModel(), d.getMaxTokens(), d.getMaxIterations(),
            d.getRag().isEnabled(), d.getRag().getTopK(), defaultPrompt);
        Optional<AgentSettingsOverride> o = store.find(key);
        Settings override = o.map(x -> new Settings(x.getModel(), x.getMaxTokens(), x.getMaxIterations(),
            x.getRagEnabled(), x.getRagTopK(), x.getSystemPrompt())).orElse(null);
        return new SettingsGroup(key, d.getSystemPrompt(), defaults, override,
            o.map(AgentSettingsOverride::getUpdatedAt).orElse(null));
    }

    private String ownerOf(String key) {
        return agents.stream()
            .map(CoreAgent::agentId)
            .filter(id -> key.equals(id) || key.startsWith(id + "-"))
            .max(Comparator.comparingInt(String::length))
            .orElse(null);
    }

    private void requireAgent(String agentId) {
        if (agents.stream().noneMatch(a -> a.agentId().equals(agentId))) {
            throw new NoSuchElementException("No such agent: " + agentId);
        }
    }

    private void requireKey(String agentId, String key) {
        requireAgent(agentId);
        if (!agentProperties.has(key) || !agentId.equals(ownerOf(key))) {
            throw new NoSuchElementException("Agent " + agentId + " has no settings key " + key);
        }
    }

    private static Settings validate(Settings s) {
        if (s == null) return new Settings(null, null, null, null, null, null);
        String model = blankToNull(s.model());
        if (model != null && (model.length() > 100 || !model.matches("[A-Za-z0-9._:/-]+"))) {
            throw new IllegalArgumentException("Model must be a model id such as claude-sonnet-5");
        }
        checkRange("Max tokens", s.maxTokens(), 1, MAX_TOKENS_LIMIT);
        checkRange("Max iterations", s.maxIterations(), 1, MAX_ITERATIONS_LIMIT);
        checkRange("RAG top-k", s.ragTopK(), 0, RAG_TOP_K_LIMIT);
        return new Settings(model, s.maxTokens(), s.maxIterations(), s.ragEnabled(), s.ragTopK(),
            blankToNull(s.systemPrompt()));
    }

    private static void checkRange(String name, Integer value, int min, int max) {
        if (value != null && (value < min || value > max)) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** One settings layer. In an override every field is optional; null inherits. */
    public record Settings(String model, Integer maxTokens, Integer maxIterations,
                           Boolean ragEnabled, Integer ragTopK, String systemPrompt) {
        boolean isEmpty() {
            return model == null && maxTokens == null && maxIterations == null
                && ragEnabled == null && ragTopK == null && systemPrompt == null;
        }
    }

    /** One config key: its deploy-time defaults, the admin override (if any) and its prompt file. */
    public record SettingsGroup(String key, String promptFile, Settings defaults, Settings override,
                                Instant updatedAt) {}
}
