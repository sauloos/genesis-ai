package ai.genesisbrands.service;

import ai.genesisbrands.model.Engagement;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.platform.FlowEngagementTrigger;
import ai.genesisbrands.repository.EngagementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

/**
 * Fills the brain-engine-core FlowEngagementTrigger extension point: a PageFlow ending
 * in CREATE_ENGAGEMENT lands here with the visitor's captured Q&A, and this creates a
 * client-sourced Engagement (no questionnaireResponseId — payment is wired separately,
 * not by this trigger). clientUserId, when the originating FlowSession was linked to an
 * authenticated ClientUser, is stamped in at creation.
 */
@Component
@RequiredArgsConstructor
public class PageFlowEngagementAdapter implements FlowEngagementTrigger {

    private final EngagementRepository engagementRepo;
    private final EngagementOrchestratorService orchestrator;

    @Override
    public String createAndRun(List<QuestionnaireQuestion> questions, List<QuestionnaireAnswer> answers, String clientUserId) {
        Engagement e = new Engagement();
        e.setId(UUID.randomUUID().toString());
        e.setSource(Engagement.Source.CLIENT);
        // Stamped in the same initial save, not as a follow-up update — the @Async
        // orchestrator below does its own find-by-id/save on a separate thread right
        // after commit, so setting this after creation would race it.
        e.setClientUserId(clientUserId);
        engagementRepo.save(e);
        String engagementId = e.getId();

        // The caller (FlowSessionService.advance()) is @Transactional, so this save isn't
        // visible to other connections yet. runEngagementWithAnswers is @Async — it runs on
        // a separate thread/transaction and would fail to find the row if fired now. Defer
        // to after the enclosing transaction commits; run immediately if there isn't one.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    orchestrator.runEngagementWithAnswers(engagementId, questions, answers);
                }
            });
        } else {
            orchestrator.runEngagementWithAnswers(engagementId, questions, answers);
        }
        return engagementId;
    }
}
