package ai.genesisbrands.service;

import ai.genesisbrands.model.FlowSession;
import ai.genesisbrands.model.Page;
import ai.genesisbrands.model.PageFlow;
import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.platform.FlowEngagementTrigger;
import ai.genesisbrands.repository.PageRepository;
import ai.genesisbrands.repository.PageWidgetRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a completed PageFlow's captured answers (FlowSession.contextJson, keyed by
 * question widget id) into the same transient QuestionnaireQuestion/QuestionnaireAnswer
 * shape BriefDerivationService already consumes, and hands them to the tenant app via
 * FlowEngagementTrigger. Triggered from FlowSessionService.advance() on a flow whose
 * endAction is CREATE_ENGAGEMENT.
 */
@Service
@RequiredArgsConstructor
public class FlowEngagementService {

    private final PageRepository pageRepo;
    private final PageWidgetRepository pageWidgetRepo;
    private final FlowEngagementTrigger flowEngagementTrigger;
    private final ObjectMapper objectMapper;

    public String triggerEngagement(PageFlow flow, FlowSession session) {
        List<String> pageIds = pageRepo.findByPageFlowId(flow.getId()).stream().map(Page::getId).toList();
        List<PageWidget> questionWidgets = pageWidgetRepo.findByPageIdInOrderByOrderInSlotAsc(pageIds).stream()
            .filter(w -> "question".equals(w.getWidgetType()))
            .toList();

        List<QuestionnaireQuestion> questions = new ArrayList<>();
        for (PageWidget widget : questionWidgets) {
            questions.add(toQuestion(widget));
        }

        Map<String, Object> context = parseContext(session.getContextJson());
        List<QuestionnaireAnswer> answers = new ArrayList<>();
        for (QuestionnaireQuestion question : questions) {
            if (!context.containsKey(question.getId())) {
                continue;
            }
            QuestionnaireAnswer answer = new QuestionnaireAnswer();
            answer.setQuestionId(question.getId());
            answer.setValueJson(toValueJson(context.get(question.getId())));
            answers.add(answer);
        }

        return flowEngagementTrigger.createAndRun(questions, answers);
    }

    private QuestionnaireQuestion toQuestion(PageWidget widget) {
        Map<String, Object> config = parseContext(widget.getConfigJson());
        QuestionnaireQuestion question = new QuestionnaireQuestion();
        question.setId(widget.getId());
        question.setPrompt(String.valueOf(config.getOrDefault("prompt", "")));
        question.setRequired(Boolean.parseBoolean(String.valueOf(config.getOrDefault("required", "false"))));
        question.setType(toQuestionType(String.valueOf(config.getOrDefault("questionType", "short_text"))));
        return question;
    }

    private QuestionnaireQuestion.Type toQuestionType(String questionType) {
        return switch (questionType) {
            case "long_text" -> QuestionnaireQuestion.Type.LONG_TEXT;
            case "single_select" -> QuestionnaireQuestion.Type.SINGLE_SELECT;
            case "multi_select" -> QuestionnaireQuestion.Type.MULTI_SELECT;
            default -> QuestionnaireQuestion.Type.SHORT_TEXT;
        };
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

    private String toValueJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "null";
        }
    }
}
