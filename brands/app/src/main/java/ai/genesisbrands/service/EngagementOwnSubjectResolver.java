package ai.genesisbrands.service;

import ai.genesisbrands.model.ClientUser;
import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.repository.EngagementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * genesis-brands' implementation of the core {@link ClientOwnSubjectResolver} SPI —
 * extraction of {@code ClientConsultantController}'s original session→subject
 * resolution (which still uses this bean, now via the generic interface) so every new
 * generic customer-facing agent controller gets the same "own finished engagement"
 * lookup without duplicating it. Unlike that controller, an unresolved client never
 * throws here — callers treat an empty result as "proceed without live context".
 */
@Component
@RequiredArgsConstructor
public class EngagementOwnSubjectResolver implements ClientOwnSubjectResolver {

    private static final String ENGAGEMENT_PREFIX = "engagement:";

    private final EngagementRepository engagementRepo;

    @Override
    public Optional<String> resolveOwnSubjectId(ClientUser client) {
        return engagementRepo.findAllByClientUserIdOrderByCreatedAtDesc(client.getId()).stream()
            .filter(e -> e.getStatus() == Engagement.Status.DONE
                && e.getPaymentStatus() == Engagement.PaymentStatus.PAID
                && e.getChosenDirection() != null)
            .findFirst()
            .map(e -> ENGAGEMENT_PREFIX + e.getId());
    }
}
