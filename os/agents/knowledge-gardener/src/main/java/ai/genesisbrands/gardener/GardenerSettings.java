package ai.genesisbrands.gardener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.NoArgsConstructor;

/** The gardener's one settings row. No row means off: a newly adopted gardener reviews nothing until an admin enables it. */
@Entity
@Table(name = "gardener_settings")
@Data
@NoArgsConstructor
public class GardenerSettings {

    public static final String ID = "default";

    @Id
    @Column(length = 20)
    private String id = ID;

    /** Review every Layer 1 ingestion automatically. Off still allows an admin's "Review now". */
    @Column(nullable = false)
    private boolean enabled;

    /** At most this many new sources are reviewed per ingestion event; a big crawl is sampled, newest first. */
    @Column(name = "max_sources_per_event", nullable = false)
    private int maxSourcesPerEvent = 10;
}
