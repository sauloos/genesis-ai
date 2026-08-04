package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "training_sessions")
@Data
@NoArgsConstructor
public class TrainingSession {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String topic;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Intent intent = Intent.KNOWLEDGE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Scope scope = Scope.GLOBAL;

    @Column(name = "asset_types")
    private String assetTypes; // comma-separated e.g. "logo,copy"

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public enum Intent   { KNOWLEDGE, GUIDELINES }
    public enum Scope    { GLOBAL, ASSET_SCOPED }
    public enum Status   { DRAFT, INGESTED }
}
