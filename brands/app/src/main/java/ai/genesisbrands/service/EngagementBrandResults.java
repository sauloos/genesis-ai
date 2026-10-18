package ai.genesisbrands.service;

import ai.genesisbrands.controller.BrandResultsEngagements;
import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.repository.EngagementRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** The brandResults widget's view of Genesis Brands engagements. */
@Component
@RequiredArgsConstructor
public class EngagementBrandResults implements BrandResultsEngagements {

    private final EngagementRepository engagementRepo;
    private final ObjectMapper objectMapper;

    @Override
    public Optional<State> find(String engagementId) {
        return engagementRepo.findById(engagementId)
            .map(e -> new State(e.getStatus().name(), hasDirections(e)));
    }

    @Override
    public Optional<String> cloneLatestFinishedFor(String clientUserId) {
        return engagementRepo.findAllByStatusOrderByCreatedAtDesc(Engagement.Status.DONE).stream()
            .filter(this::hasDirections)
            .findFirst()
            .map(source -> {
                // A fresh row owned by this client rather than the shared source row, so the
                // existing ownership checks apply with no change to auth logic.
                Engagement clone = new Engagement();
                clone.setId(UUID.randomUUID().toString());
                clone.setSource(Engagement.Source.CLIENT);
                clone.setStatus(Engagement.Status.DONE);
                clone.setEvalMode(source.isEvalMode());
                clone.setBriefsJson(source.getBriefsJson());
                clone.setResultsJson(source.getResultsJson());
                clone.setClientUserId(clientUserId);
                return engagementRepo.save(clone).getId();
            });
    }

    private boolean hasDirections(Engagement e) {
        if (e.getResultsJson() == null) return false;
        try {
            return !objectMapper.readTree(e.getResultsJson()).path("directions").isEmpty();
        } catch (Exception ex) {
            return false;
        }
    }
}
