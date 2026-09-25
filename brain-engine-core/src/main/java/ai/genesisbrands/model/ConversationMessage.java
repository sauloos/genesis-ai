package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "conversation_messages")
@Data
@NoArgsConstructor
public class ConversationMessage {

    @Id
    @Column(length = 36)
    private String id;

    // Physical column name kept as brand_id — predates the platform/tenant split and
    // renaming it would require a migration for no functional benefit.
    @Column(name = "brand_id", nullable = false, length = 36)
    private String subjectId;

    @Column(nullable = false, length = 10)
    private String role;  // "user" or "assistant"

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    // Nullable so adding this column to an already-populated table doesn't require a
    // migration under Hibernate ddl-auto=update; existing rows read back as null and are
    // treated as CONSULTANT (see ConsultantService/ConversationMessageRepository).
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Source source;

    public enum Source { CONSULTANT, PLAYGROUND }
}
