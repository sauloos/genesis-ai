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

    /**
     * The Layer 1 signal this question captures, e.g. {@code belief.origin} or
     * {@code belong.persona[0].story}. This is the contract between the questionnaire, the
     * agents that read its answers, and that client's RAG context — downstream consumers key
     * on the signal, never on the question's UUID, so a question can be reworded, reordered or
     * replaced without breaking anything. Null for a question that captures no catalogued
     * signal (a free-form or legacy question).
     */
    @Column(name = "signal_key", length = 120)
    private String signalKey;

    /** The act this question belongs to ({@code setup}, {@code belief}, … ), for progress. */
    @Column(name = "section_key", length = 60)
    private String sectionKey;

    /**
     * Questions sharing a step render on one screen — the six-blank value-proposition ladder
     * and the five inner-circle roles are each one step, not six or five separate screens.
     * Null means the question is its own step.
     */
    @Column(name = "step_key", length = 60)
    private String stepKey;

    /**
     * When set, the question only applies if this holds against the answers so far. The
     * grammar is deliberately tiny — see {@code QuestionCondition}. An unparseable condition
     * shows the question rather than hiding it.
     */
    @Column(name = "condition_expr", length = 240)
    private String conditionExpr;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * How the question is answered. Stored as a string, so adding a value is schema-safe.
     * The widget resolves each one to a renderer; everything a renderer needs beyond the
     * prompt (options, bounds, labels, artwork) lives in {@link #configJson}.
     */
    public enum Type {
        SHORT_TEXT,
        LONG_TEXT,
        URL,
        SLIDER,
        SINGLE_SELECT,
        MULTI_SELECT,
        /** Pick from a word bank, with room to add your own. */
        CHIP_PICK,
        /** Order a previously-picked set by importance. */
        RANKING,
        /** Choose from labelled icons (the UN goals, for instance). */
        ICON_GRID,
        /** Choose from abstract reference imagery. */
        IMAGE_CHOICE,
        /** Choose from colour palettes. */
        PALETTE_CHOICE,
        /** Choose from type specimens. */
        TYPE_SPECIMEN,
        IMAGE_UPLOAD,
        /** Any file — a logo in AI, EPS or PDF, a brand guideline deck. */
        FILE_UPLOAD,
        /** Shown-back synthesis the client confirms or corrects, never a blank question. */
        SYNTHESIS
    }
}
