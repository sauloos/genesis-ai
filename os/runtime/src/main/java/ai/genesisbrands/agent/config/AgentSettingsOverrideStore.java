package ai.genesisbrands.agent.config;

import ai.genesisbrands.model.AgentSettingsOverride;
import ai.genesisbrands.repository.AgentSettingsOverrideRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Read side of the admin settings layer. Agents read their config on every call, so the
 * overrides are held in memory and reloaded at most every {@link #TTL}. A local save or
 * delete refreshes at once; other replicas pick the change up within the TTL.
 */
@Service
public class AgentSettingsOverrideStore {

    static final Duration TTL = Duration.ofSeconds(10);

    private final AgentSettingsOverrideRepository repository;
    private volatile Map<String, AgentSettingsOverride> cache;
    private volatile Instant loadedAt = Instant.EPOCH;

    public AgentSettingsOverrideStore(AgentSettingsOverrideRepository repository) {
        this.repository = repository;
    }

    public Optional<AgentSettingsOverride> find(String configKey) {
        return Optional.ofNullable(snapshot().get(configKey));
    }

    public AgentSettingsOverride save(AgentSettingsOverride override) {
        override.setUpdatedAt(Instant.now());
        AgentSettingsOverride saved = repository.save(override);
        invalidate();
        return saved;
    }

    public void delete(String configKey) {
        if (repository.existsById(configKey)) repository.deleteById(configKey);
        invalidate();
    }

    private Map<String, AgentSettingsOverride> snapshot() {
        Map<String, AgentSettingsOverride> current = cache;
        if (current == null || Instant.now().isAfter(loadedAt.plus(TTL))) {
            current = repository.findAll().stream()
                .collect(Collectors.toUnmodifiableMap(AgentSettingsOverride::getConfigKey, Function.identity()));
            cache = current;
            loadedAt = Instant.now();
        }
        return current;
    }

    private void invalidate() {
        cache = null;
    }
}
