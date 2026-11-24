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
        return findOwnEngagement(client.getId())
            .or(() -> findEngagementWithPlaybook(client.getId()))
            .map(e -> ENGAGEMENT_PREFIX + e.getId());
    }

    /**
     * The client's latest engagement that has a Purpose Playbook, whatever state it is in — the
     * fallback when they have no finished, paid, chosen engagement yet.
     *
     * <p>A client is at their most talkative between finishing discovery and choosing a direction:
     * the playbook exists, the deliverables are generating, and they want to ask about what they
     * just said. Resolving to nothing there left every customer-facing surface — the consultant,
     * the connectors, a standing run — with no idea who they were talking to, while the client's
     * own agreed strategic foundation sat in the database. The playbook is written before the
     * briefs, so it is available for exactly that window.
     *
     * <p>The strict lookup still comes first, because an engagement with a chosen direction carries
     * the real deliverables as well as the playbook.
     */
    private Optional<Engagement> findEngagementWithPlaybook(String clientUserId) {
        return engagementRepo.findAllByClientUserIdOrderByCreatedAtDesc(clientUserId).stream()
            .filter(e -> e.getPurposePlaybookJson() != null && !e.getPurposePlaybookJson().isBlank())
            .findFirst();
    }

    /**
     * The client's latest paid, finished engagement with a chosen direction — their "own" brand.
     *
     * <p>Kept strict: its other caller regenerates assets from the chosen direction's output and
     * needs an engagement that actually has one.
     */
    public Optional<Engagement> findOwnEngagement(String clientUserId) {
        return engagementRepo.findAllByClientUserIdOrderByCreatedAtDesc(clientUserId).stream()
            .filter(e -> e.getStatus() == Engagement.Status.DONE
                && e.getPaymentStatus() == Engagement.PaymentStatus.PAID
                && e.getChosenDirection() != null)
            .findFirst();
    }
}
