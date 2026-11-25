package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One example in the case library (see {@code KnowledgeCases}): an image or a piece of work an
 * admin marked good or bad, with the lesson it teaches. Verdict and status are plain strings, not
 * enum columns, so a new value never needs a schema change.
 */
@Entity
@Table(name = "knowledge_cases")
@Data
@NoArgsConstructor
public class KnowledgeCase {

    public static final String GOOD = "GOOD";
    public static final String BAD = "BAD";
    public static final String DRAFT = "DRAFT";
    public static final String APPROVED = "APPROVED";

    @Id
    @Column(length = 36)
    private String id;

    /** What the case is an example of, e.g. "logo". Agents ask for one kind. */
    @Column(nullable = false, length = 40)
    private String kind;

    /** GOOD or BAD. */
    @Column(nullable = false, length = 10)
    private String verdict;

    /** DRAFT until an admin approves it; agents read only APPROVED cases. */
    @Column(nullable = false, length = 16)
    private String status = DRAFT;

    private String title;

    private String sector;

    /** Comma-separated, e.g. "negative_space,wordmark,coffee". */
    @Column(length = 500)
    private String tags;

    /** The admin's own note on why it is good or bad: the starting point of the analysis. */
    @Column(columnDefinition = "TEXT")
    private String note;

    /** What the work is and why it works or fails, drafted by the case drafter and edited by the admin. */
    @Column(columnDefinition = "TEXT")
    private String analysis;

    /** The rule the case teaches, in one sentence. */
    @Column(columnDefinition = "TEXT")
    private String lesson;

    /** Where the image lives in blob storage; null for a case without an image. */
    @Column(name = "image_path")
    private String imagePath;

    @Column(name = "image_type", length = 40)
    private String imageType;

    @Column(name = "source_url", length = 1000)
    private String sourceUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
