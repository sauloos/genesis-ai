package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "questionnaire_responses")
@Data
@NoArgsConstructor
public class QuestionnaireResponse {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "questionnaire_id", nullable = false, length = 36)
    private String questionnaireId;

    @Column(name = "respondent_label")
    private String respondentLabel;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.IN_PROGRESS;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public enum Status { IN_PROGRESS, SUBMITTED }
}
