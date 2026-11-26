package ai.genesisbrands.widget.logoassessment;

import java.util.Map;

/**
 * Where the widget keeps its result in the flow session context:
 * {@code __logoAssessment:<widgetId>} → {@code {status, logoUrl?, assessment?, intent?, decision?}}.
 */
final class LogoAssessmentRecord {

    static final String KEY_PREFIX = "__logoAssessment:";
    /** The questionnaire widget's own key for its response id (see its widget.js). */
    static final String RESPONSE_KEY_PREFIX = "__questionnaireResponse:";

    private LogoAssessmentRecord() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> read(Map<String, Object> context, String widgetId) {
        Object value = context.get(KEY_PREFIX + widgetId);
        return value instanceof Map<?, ?> m && m.get("status") != null ? (Map<String, Object>) m : null;
    }
}
