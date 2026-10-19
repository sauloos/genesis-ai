package ai.genesisbrands.platform;

import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;

import java.util.List;
import java.util.Map;

/**
 * A widget type whose answers a CREATE_ENGAGEMENT flow end hands to the tenant's
 * FlowEngagementTrigger. The widget's own module provides the bean, so core never names a
 * widget type: a flow can end in CREATE_ENGAGEMENT when it holds at least one widget whose
 * type has a source.
 */
public interface FlowAnswerSource {

    String widgetType();

    /**
     * The questions this widget asked and the answers the visitor gave, read from the flow
     * session context. Empty when the visitor never reached or finished the widget.
     *
     * @param config the widget's parsed configJson (empty when it has none)
     */
    FlowAnswers collect(PageWidget widget, Map<String, Object> config, Map<String, Object> context);

    record FlowAnswers(List<QuestionnaireQuestion> questions, List<QuestionnaireAnswer> answers) {
        public static final FlowAnswers NONE = new FlowAnswers(List.of(), List.of());
    }
}
