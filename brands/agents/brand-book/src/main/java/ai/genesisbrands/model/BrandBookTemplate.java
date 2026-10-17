package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "brand_book_templates")
@Data
@NoArgsConstructor
public class BrandBookTemplate {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.CURRENT;

    /**
     * Classpath-relative path to the HTML template file,
     * e.g. "templates/brand-book/template-classic.html".
     */
    @Column(name = "classpath_path", nullable = false)
    private String classpathPath;

    /**
     * JSON array of section descriptors: [{id, name, required, defaultIncluded}].
     * Drives the admin UI section checklist and the renderer's section filter.
     */
    @Column(name = "section_manifest_json", columnDefinition = "TEXT")
    private String sectionManifestJson;

    /**
     * JSON object with orchestrator selection hints:
     * {format, suitableFor, archetypes, selectionWeight}.
     */
    @Column(name = "selection_hints_json", columnDefinition = "TEXT")
    private String selectionHintsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public enum Status { CURRENT, DEPRECATED }
}
