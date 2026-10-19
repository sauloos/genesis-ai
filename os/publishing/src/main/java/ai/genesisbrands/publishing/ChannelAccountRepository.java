package ai.genesisbrands.publishing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ChannelAccountRepository extends JpaRepository<ChannelAccount, String> {

    List<ChannelAccount> findByClientUserIdOrderByConnectedAtAsc(String clientUserId);

    Optional<ChannelAccount> findByIdAndClientUserId(String id, String clientUserId);

    Optional<ChannelAccount> findByClientUserIdAndProviderAndNetworkAndExternalId(String clientUserId, String provider,
                                                                                 String network, String externalId);

    List<ChannelAccount> findByStatusAndExpiresAtLessThan(String status, Instant before);

    long countByStatus(String status);
}
