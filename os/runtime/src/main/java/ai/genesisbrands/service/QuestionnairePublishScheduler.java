package ai.genesisbrands.service;

import ai.genesisbrands.model.Questionnaire;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class QuestionnairePublishScheduler {

    private final QuestionnaireService questionnaireService;

    @Scheduled(fixedRate = 60000)
    @Transactional
    public void promoteScheduled() {
        for (Questionnaire q : questionnaireService.findScheduled()) {
            if (!q.getPublishAt().isAfter(Instant.now())) {
                questionnaireService.publish(q.getId(), q.getPublishAt());
            }
        }
    }
}
