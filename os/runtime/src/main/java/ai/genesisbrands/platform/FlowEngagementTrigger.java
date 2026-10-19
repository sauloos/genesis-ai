package ai.genesisbrands.platform;

import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;

import java.util.List;

/**
 * Extension point: brain-engine-core has no compile-time dependency on the Engagement
 * pipeline (it lives in the tenant app, e.g. genesis-brands), so a PageFlow ending in
 * CREATE_ENGAGEMENT resolves this interface via Spring DI instead of calling it
 * directly. Mirrors WidgetDescriptor's core-defines/tenant-fills-in pattern.
 */
public interface FlowEngagementTrigger {

    /** Creates a new Engagement from the given Q&A and starts the pipeline asynchronously.
     *  clientUserId (nullable) is the ClientUser the originating FlowSession was linked to,
     *  if any, and is stamped onto the Engagement at creation. Returns the new engagement's id. */
    String createAndRun(List<QuestionnaireQuestion> questions, List<QuestionnaireAnswer> answers, String clientUserId);

    /** Where the visitor goes once the flow has started this engagement — e.g. the tenant's
     *  results page, or a sign-up page that then claims it for the new account. Core only
     *  navigates there; null shows the plain completion screen. */
    default String nextUrl(String engagementId, String clientUserId) {
        return null;
    }
}
