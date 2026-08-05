package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "training_content")
@Data
@NoArgsConstructor
public class TrainingContent {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "session_id", nullable = false, length = 36)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false)
    private ContentType contentType;

    @Column(name = "text_content", columnDefinition = "TEXT")
    private String textContent;

    @Column(name = "blob_path")
    private String blobPath;

    @Column(name = "file_name")
    private String fileName;

    @Column(columnDefinition = "TEXT")
    private String transcript;

    @Enumerated(EnumType.STRING)
    private Label label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public enum ContentType { TEXT, FILE, AUDIO, ASSET }
    public enum Label { POSITIVE, NEGATIVE }
}
