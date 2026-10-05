package ai.genesisbrands.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AgentRegenerationJobSchemaFixer {

    private static final Logger log = LoggerFactory.getLogger(AgentRegenerationJobSchemaFixer.class);

    private final JdbcTemplate jdbcTemplate;

    public AgentRegenerationJobSchemaFixer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void widenStaleStatusConstraint() {
        dropStalePostgresCheckConstraint();
        widenStaleH2EnumColumn();
    }

    private void dropStalePostgresCheckConstraint() {
        try {
            List<String> names = jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints " +
                "WHERE table_name = 'agent_regeneration_jobs' AND constraint_type = 'CHECK'",
                String.class
            );
            for (String constraintName : names) {
                String definition = jdbcTemplate.queryForObject(
                    "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = ?",
                    String.class, constraintName
                );
                if (definition != null && definition.contains("status") && !definition.contains("AWAITING_APPROVAL")) {
                    jdbcTemplate.execute("ALTER TABLE agent_regeneration_jobs DROP CONSTRAINT " + constraintName);
                    log.info("Dropped stale agent_regeneration_jobs status check constraint: {}", constraintName);
                }
            }
        } catch (Exception ex) {
            log.debug("Could not check/drop stale agent_regeneration_jobs status check constraint (likely not Postgres)", ex);
        }
    }

    private void widenStaleH2EnumColumn() {
        try {
            String dataType = jdbcTemplate.queryForObject(
                "SELECT data_type FROM information_schema.columns " +
                "WHERE table_name = 'AGENT_REGENERATION_JOBS' AND column_name = 'STATUS'",
                String.class
            );
            if ("ENUM".equals(dataType)) {
                jdbcTemplate.execute(
                    "ALTER TABLE AGENT_REGENERATION_JOBS ALTER COLUMN STATUS " +
                    "ENUM('QUEUED','RUNNING','AWAITING_APPROVAL','DONE','REJECTED','FAILED')"
                );
                log.info("Widened AGENT_REGENERATION_JOBS.STATUS enum column to include AWAITING_APPROVAL/REJECTED");
            }
        } catch (Exception ex) {
            log.debug("Could not check/widen AGENT_REGENERATION_JOBS.STATUS enum column (likely not H2)", ex);
        }
    }
}
