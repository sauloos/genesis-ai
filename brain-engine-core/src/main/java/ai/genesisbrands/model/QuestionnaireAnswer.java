package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "questionnaire_answers")
@Data
@NoArgsConstructor
public class QuestionnaireAnswer {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "response_id", nullable = false, length = 36)
    private String responseId;

    @Column(name = "question_id", nullable = false, length = 36)
    private String questionId;

    @Column(name = "value_json", columnDefinition = "TEXT")
    private String valueJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
