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
import ai.genesisbrands.repository.QuestionnaireAnswerRepository;
import ai.genesisbrands.repository.QuestionnaireQuestionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private final QuestionnaireQuestionRepository questionnaireQuestionRepo;
    private final QuestionnaireAnswerRepository questionnaireAnswerRepo;
    private final Optional<FlowEngagementTrigger> flowEngagementTrigger;
    private final ObjectMapper objectMapper;

    public String triggerEngagement(PageFlow flow, FlowSession session) {
        List<PageWidget> widgets = pageWidgetRepo.findByPageIdInOrderByOrderInSlotAsc(
            pageRepo.findByPageFlowId(flow.getId()).stream().map(Page::getId).toList());
        Map<String, Object> context = parseContext(session.getContextJson());

        List<QuestionnaireQuestion> questions = new ArrayList<>();
        List<QuestionnaireAnswer> answers = new ArrayList<>();

        for (PageWidget widget : widgets) {
            if ("question".equals(widget.getWidgetType())) {
                QuestionnaireQuestion question = toQuestion(widget);
                questions.add(question);
                if (context.containsKey(question.getId())) {
                    QuestionnaireAnswer answer = new QuestionnaireAnswer();
                    answer.setQuestionId(question.getId());
                    answer.setValueJson(toValueJson(context.get(question.getId())));
                    answers.add(answer);
                }
            } else if ("questionnaire".equals(widget.getWidgetType())) {
                Object responseId = context.get("__questionnaireResponse:" + widget.getId());
                if (responseId == null) {
                    continue; // visitor never reached/completed this branch
                }
                Map<String, Object> config = parseContext(widget.getConfigJson());
                String questionnaireId = String.valueOf(config.getOrDefault("questionnaireId", ""));
                questions.addAll(questionnaireQuestionRepo.findByQuestionnaireIdOrderByOrderIndexAsc(questionnaireId));
                answers.addAll(questionnaireAnswerRepo.findByResponseIdOrderByCreatedAtAsc(String.valueOf(responseId)));
            }
        }

        return flowEngagementTrigger
            .orElseThrow(() -> new IllegalStateException(
                "No FlowEngagementTrigger bean available — this tenant app doesn't support CREATE_ENGAGEMENT flows"))
            .createAndRun(questions, answers);
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
