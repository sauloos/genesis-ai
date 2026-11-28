package ai.genesisbrands.previews;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One row per preview manifest and per page served: who looked at what, when, from where, and
 * under which view code (the code is also woven into the page's watermark, so a leaked
 * screenshot traces back to one viewing).
 */
@Entity
@Table(name = "preview_view_log", indexes = {
    @Index(name = "idx_preview_log_client", columnList = "client_user_id, created_at"),
    @Index(name = "idx_preview_log_code", columnList = "view_code")
})
@Data
@NoArgsConstructor
public class PreviewViewLog {

    public static final String MANIFEST = "MANIFEST";
    public static final String PAGE = "PAGE";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "view_code", nullable = false, length = 16)
    private String viewCode;

    @Column(name = "client_user_id", nullable = false, length = 36)
    private String clientUserId;

    @Column(name = "work_item_id", nullable = false, length = 64)
    private String workItemId;

    @Column(name = "variant_key", nullable = false, length = 64)
    private String variantKey;

    @Column(nullable = false, length = 12)
    private String event;

    @Column(name = "page_index")
    private Integer pageIndex;

    @Column(length = 64)
    private String ip;

    @Column(name = "user_agent", length = 200)
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
