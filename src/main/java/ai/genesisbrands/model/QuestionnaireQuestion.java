package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "questionnaire_questions")
@Data
@NoArgsConstructor
public class QuestionnaireQuestion {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "questionnaire_id", nullable = false, length = 36)
    private String questionnaireId;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Column(name = "help_text", columnDefinition = "TEXT")
    private String helpText;

    @Column(nullable = false)
    private boolean required = false;

    @Column(name = "allow_voice", nullable = false)
    private boolean allowVoice = false;

    @Column(name = "config_json", columnDefinition = "TEXT")
    private String configJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public enum Type { SHORT_TEXT, LONG_TEXT, IMAGE_UPLOAD, SLIDER, SINGLE_SELECT, MULTI_SELECT }
}
