package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Admin-set override of one agent config key's runtime settings — the top layer over the
 * agent module's {@code agents/<key>/agent.yml} and the app's {@code application.yml}
 * (see AgentProperties). Keyed by config key, not agentId: one agent can own several keys
 * (e.g. {@code copy} and {@code copy-evaluator}). Every field is nullable; null means "inherit
 * the lower layers", so a row only ever records what an admin actually changed.
 */
@Entity
@Table(name = "agent_settings_overrides")
@Data
@NoArgsConstructor
public class AgentSettingsOverride {

    @Id
    @Column(name = "config_key", length = 64)
    private String configKey;

    private String model;

    @Column(name = "max_tokens")
    private Integer maxTokens;

    @Column(name = "max_iterations")
    private Integer maxIterations;

    @Column(name = "rag_enabled")
    private Boolean ragEnabled;

    @Column(name = "rag_top_k")
    private Integer ragTopK;

    /** Full prompt text replacing the classpath prompt file. */
    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
