package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "playground_sessions")
@Data
@NoArgsConstructor
public class PlaygroundSession {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "agent_id", nullable = false)
    private String agentId;

    @Column(name = "method")
    private String method;

    @Column(name = "compare_mode", nullable = false)
    private boolean compareMode;

    @Column(name = "evaluate_mode", nullable = false)
    private boolean evaluateMode;

    @Column(nullable = false)
    private String label;

    @Column(name = "brief_json", columnDefinition = "TEXT", nullable = false)
    private String briefJson;

    @Column(name = "result_json", columnDefinition = "TEXT", nullable = false)
    private String resultJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
