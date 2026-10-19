package ai.genesisbrands.service;

import ai.genesisbrands.model.FlowSession;
import ai.genesisbrands.model.Page;
import ai.genesisbrands.model.PageFlow;
import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.platform.FlowAnswerSource;
import ai.genesisbrands.platform.FlowEngagementTrigger;
import ai.genesisbrands.repository.PageRepository;
import ai.genesisbrands.repository.PageWidgetRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Turns a completed PageFlow's captured answers (FlowSession.contextJson) into the
 * QuestionnaireQuestion/QuestionnaireAnswer shape BriefDerivationService already consumes,
 * and hands them to the tenant app via FlowEngagementTrigger. Each widget type that captures
 * answers reads its own through a FlowAnswerSource bean from its module. Triggered from
 * FlowSessionService.advance() on a flow whose endAction is CREATE_ENGAGEMENT.
 */
@Service
public class FlowEngagementService {

    private final PageRepository pageRepo;
    private final PageWidgetRepository pageWidgetRepo;
    private final Map<String, FlowAnswerSource> answerSources;
    private final Optional<FlowEngagementTrigger> flowEngagementTrigger;
    private final ObjectMapper objectMapper;

    public FlowEngagementService(PageRepository pageRepo, PageWidgetRepository pageWidgetRepo,
                                 List<FlowAnswerSource> answerSources,
                                 Optional<FlowEngagementTrigger> flowEngagementTrigger, ObjectMapper objectMapper) {
        this.pageRepo = pageRepo;
        this.pageWidgetRepo = pageWidgetRepo;
        this.answerSources = answerSources.stream()
            .collect(Collectors.toMap(FlowAnswerSource::widgetType, Function.identity()));
        this.flowEngagementTrigger = flowEngagementTrigger;
        this.objectMapper = objectMapper;
    }

    /** Starts the tenant's engagement and returns its id. */
    public String triggerEngagement(PageFlow flow, FlowSession session) {
        List<PageWidget> widgets = pageWidgetRepo.findByPageIdInOrderByOrderInSlotAsc(
            pageRepo.findByPageFlowId(flow.getId()).stream().map(Page::getId).toList());
        Map<String, Object> context = parseContext(session.getContextJson());

        List<QuestionnaireQuestion> questions = new ArrayList<>();
        List<QuestionnaireAnswer> answers = new ArrayList<>();

        for (PageWidget widget : widgets) {
            FlowAnswerSource source = answerSources.get(widget.getWidgetType());
            if (source == null) {
                continue;
            }
            FlowAnswerSource.FlowAnswers collected = source.collect(widget, parseContext(widget.getConfigJson()), context);
            questions.addAll(collected.questions());
            answers.addAll(collected.answers());
        }

        return trigger().createAndRun(questions, answers, session.getClientUserId());
    }

    /** Where a visitor goes when their flow ends in this engagement (null = stay on the flow). */
    public String nextUrl(String engagementId, FlowSession session) {
        return trigger().nextUrl(engagementId, session.getClientUserId());
    }

    private FlowEngagementTrigger trigger() {
        return flowEngagementTrigger.orElseThrow(() -> new IllegalStateException(
            "No FlowEngagementTrigger bean available — this tenant app doesn't support CREATE_ENGAGEMENT flows"));
    }

    private Map<String, Object> parseContext(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }
}
