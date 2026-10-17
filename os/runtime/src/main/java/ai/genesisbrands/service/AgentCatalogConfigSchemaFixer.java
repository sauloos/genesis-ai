package ai.genesisbrands.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Adds the {@code tools_enabled} column to {@code agent_catalog_configs} if Hibernate's
 * {@code ddl-auto=update} failed to. Hibernate generates {@code ADD COLUMN ... NOT NULL} with
 * no {@code DEFAULT}, which Postgres rejects once the table already has rows (existing agent
 * config rows get NULL for the new column, violating NOT NULL) — the ALTER is logged as a
 * warning and skipped, not fatal, so the app boots with the column silently missing and every
 * later read/write of AgentCatalogConfig 500s. {@code ADD COLUMN IF NOT EXISTS ... DEFAULT
 * false} is idempotent and valid on both Postgres and H2, so one statement covers both
 * dialects — no dialect branching needed here, unlike the enum-widening fixers.
 */
@Component
public class AgentCatalogConfigSchemaFixer {

    private static final Logger log = LoggerFactory.getLogger(AgentCatalogConfigSchemaFixer.class);

    private final JdbcTemplate jdbcTemplate;

    public AgentCatalogConfigSchemaFixer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void addMissingToolsEnabledColumn() {
        try {
            jdbcTemplate.execute(
                "ALTER TABLE agent_catalog_configs ADD COLUMN IF NOT EXISTS tools_enabled boolean NOT NULL DEFAULT false"
            );
            log.info("Verified agent_catalog_configs.tools_enabled column exists");
        } catch (Exception ex) {
            log.warn("Could not add/verify agent_catalog_configs.tools_enabled column", ex);
        }
    }
}
