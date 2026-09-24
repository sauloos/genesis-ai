package ai.genesisbrands.widget;

import ai.genesisbrands.platform.WidgetConfigOption;
import ai.genesisbrands.platform.WidgetConfigOption.OptionType;
import ai.genesisbrands.platform.WidgetDescriptor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Embeds an existing Questionnaire's full question set inside a single PageFlow page —
 * the visitor steps through all of its questions internally via /api/questionnaires/**,
 * with no page-to-page transition until they submit. Unlike QuestionWidget (one field,
 * one page), this reuses the mature Questionnaire question-type system unchanged.
 */
@Component
public class QuestionnaireWidget implements WidgetDescriptor {

    @Override
    public String widgetType() {
        return "questionnaire";
    }

    @Override
    public String displayName() {
        return "Questionnaire";
    }

    @Override
    public String description() {
        return "Embeds an existing questionnaire's full question set, stepped through in place.";
    }

    @Override
    public List<WidgetConfigOption> configOptions() {
        return List.of(
            new WidgetConfigOption("questionnaireId", "Questionnaire", OptionType.STRING, List.of(), "")
        );
    }
}
