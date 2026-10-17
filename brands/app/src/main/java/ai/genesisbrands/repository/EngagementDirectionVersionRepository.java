package ai.genesisbrands.repository;

import ai.genesisbrands.model.EngagementDirectionVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EngagementDirectionVersionRepository extends JpaRepository<EngagementDirectionVersion, String> {
    long countByEngagementIdAndDirection(String engagementId, String direction);

    List<EngagementDirectionVersion> findByEngagementIdAndDirectionOrderByVersionNumberDesc(
        String engagementId, String direction);

    Optional<EngagementDirectionVersion> findByEngagementIdAndDirectionAndVersionNumber(
        String engagementId, String direction, int versionNumber);
}
