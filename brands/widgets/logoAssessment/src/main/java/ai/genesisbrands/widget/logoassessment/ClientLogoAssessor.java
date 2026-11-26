package ai.genesisbrands.widget.logoassessment;

import java.util.Map;

/**
 * What the logoAssessment widget needs from the app: an assessment of the logo a client uploaded
 * in discovery. The adopting app implements it over its logo audit, so this module never depends
 * on an agent module.
 */
public interface ClientLogoAssessor {

    enum Status {
        /** Assessed; {@code assessment} holds the result. */
        ASSESSED,
        /** The client did not upload a logo. */
        NO_LOGO,
        /** The file is a type the audit cannot look at yet (an SVG, a PDF); the team reviews it. */
        UNVIEWABLE
    }

    /**
     * @param status     what happened
     * @param logoUrl    where the uploaded logo is served, or null
     * @param assessment the assessment as plain JSON values (score, verdict, criteria, keep,
     *                   change, …), or null unless ASSESSED
     */
    record Result(Status status, String logoUrl, Map<String, Object> assessment) {}

    /**
     * Assesses the client's logo from their discovery answers.
     *
     * @param signals the answers so far, keyed by Layer 1 signal key ({@code look.currentLogo},
     *                {@code look.currentLogoFeelings}, {@code look.logoIntent} and the rest as
     *                brand context)
     */
    Result assess(Map<String, Object> signals);
}
