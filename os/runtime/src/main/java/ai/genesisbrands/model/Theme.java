package ai.genesisbrands.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "themes")
@Data
@NoArgsConstructor
public class Theme {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 32)
    private String version;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private boolean active = false;

    @Column(nullable = false)
    private boolean builtIn = false;

    @Column(name = "css_content", nullable = false, columnDefinition = "TEXT")
    private String cssContent = "";

    @Lob
    @Column(name = "logo_content")
    private byte[] logoContent;

    @Column(name = "logo_content_type", length = 64)
    private String logoContentType;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}
