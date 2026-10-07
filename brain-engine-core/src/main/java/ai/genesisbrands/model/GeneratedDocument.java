package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One past generation from a CREATE-shaped generic core agent (Summarizer, RFP Response,
 * Research & Synthesis, Writer) — append-only, never overwritten, unlike a SLOT
 * specialist's output. Deliberately generic/freeform (markdown content, no structured
 * schema) rather than routed through {@code ClientWorkspaceProvider}, whose shape is
 * built around Engagement/direction-variant assets and doesn't fit this data.
 */
@Entity
@Table(name = "generated_documents")
@Data
@NoArgsConstructor
public class GeneratedDocument {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "agent_id", nullable = false, length = 64)
    private String agentId;

    // Null on a Playground-generated document (no owning client).
    @Column(name = "client_user_id", length = 64)
    private String clientUserId;

    @Column(length = 200)
    private String title;

    @Column(name = "content_markdown", nullable = false, columnDefinition = "TEXT")
    private String contentMarkdown;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
