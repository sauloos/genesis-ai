package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "questionnaires")
@Data
@NoArgsConstructor
public class Questionnaire {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * The acts this questionnaire is divided into, as a JSON array of
     * {@code {"key","title","intent"}} — the keys a question's {@code sectionKey} refers to.
     * Titles live here rather than on every question so a rename is one edit, and so the
     * client-side progress UI can read the whole structure in one go. Null for a flat
     * questionnaire with no acts.
     */
    @Column(name = "sections_json", columnDefinition = "TEXT")
    private String sectionsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(name = "publish_at")
    private Instant publishAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public enum Status { DRAFT, SCHEDULED, PUBLISHED, ARCHIVED }
}
