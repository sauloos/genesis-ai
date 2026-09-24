package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetConfigOption;
import ai.genesisbrands.platform.WidgetConfigOption.OptionType;
import ai.genesisbrands.platform.WidgetDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Captures a single answer from the visitor into FlowSession.contextJson, keyed by
 * this widget's id. Feeds a PageFlow with a CREATE_ENGAGEMENT end action (see
 * FlowEngagementService), the PageFlow analogue of a QuestionnaireQuestion.
 */
@Component
public class QuestionWidget implements WidgetDescriptor {

    @Override
    public String widgetType() {
        return "question";
    }

    @Override
    public String displayName() {
        return "Question";
    }

    @Override
    public String description() {
        return "Captures a text or choice answer from the visitor.";
    }

    @Override
    public List<WidgetConfigOption> configOptions() {
        return List.of(
            new WidgetConfigOption("prompt", "Prompt", OptionType.STRING, List.of(), ""),
            new WidgetConfigOption("helpText", "Help text", OptionType.STRING, List.of(), ""),
            new WidgetConfigOption("required", "Required", OptionType.BOOLEAN, List.of(), "false"),
            new WidgetConfigOption("questionType", "Question type", OptionType.SELECT,
                List.of("short_text", "long_text", "single_select", "multi_select"), "short_text"),
            new WidgetConfigOption("choicesJson", "Choices (JSON array)", OptionType.STRING, List.of(), "[]")
        );
    }
}
