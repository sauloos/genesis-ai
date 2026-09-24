package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * GenesisOS' consultant subject: unlike genesis-brands (where a subject is a Brand),
 * the platform has no per-tenant "brand" concept — a subject here is simply a named
 * consultation thread (mirrors how console.html already treats subjects: one per
 * conversation, auto-created on first message).
 */
@Entity
@Table(name = "consultation_subjects")
@Data
@NoArgsConstructor
public class ConsultationSubject {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String brief;

    @Column(columnDefinition = "TEXT")
    private String industry;

    @Column(columnDefinition = "TEXT")
    private String audience;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
