package ai.genesisbrands.agent.core;

import java.util.List;

/**
 * What every specialist agent is briefed from: one of the three creative directions, carrying the
 * shared brand foundation plus that direction's own slant.
 *
 * <p>{@code purpose} is the Purpose Playbook derivation and is null on the legacy path. A brief
 * derived from a questionnaire that carries Layer 1 signal keys goes through the playbook, and the
 * richer material {@code directions/purpose-to-direction.yaml}'s output contract asks for lands in
 * that record. It is kept typed <em>and</em> rendered into {@code additionalContext}, so a
 * specialist that has not been taught to read it still receives every word of it.
 */
public record DirectionBrief(
    String engagementId,
    BrandContext brand,
    BrandFoundation foundation,
    CreativeDirection direction,
    List<String> trainingInstructions,
    String additionalContext,
    PurposeDerivation purpose
) {
    public record BrandContext(
        String name,
        String industry,
        String targetAudience,
        String coreOffer,
        String differentiator,
        List<String> personality,
        String tone
    ) {}

    public record BrandFoundation(
        String differentiator,
        String targetAudiencePersona,
        String corePositioning,
        String toneSpectrum
    ) {}

    /**
     * The direction as {@code directions/purpose-to-direction.yaml} defines it, one field per item
     * of its output contract.
     *
     * <p>{@code invariants} is the part that is deliberately identical across all three
     * directions: the module lists what must survive every direction (mission, audience, the
     * proposition's substance, the never-list, the forbidden colours, any accreditation mark), and
     * a direction that drops one of them is not a bolder direction but a different brand.
     *
     * @param summary      one sentence a non-designer could repeat back
     * @param tension      the tension this direction resolves
     * @param archetype    the primary archetype from {@code directions/archetypes.yaml}
     * @param tonalRegister how the brand sounds in this direction
     * @param visualTerritory the visual territory, in prose
     * @param retains      what this direction keeps from where the client is now
     * @param releases     what it deliberately lets go of
     * @param audienceEmphasis which persona or segment it leans on
     * @param bet          what it is betting on
     * @param risk         what it risks — stated, never hidden, so the client chooses knowingly
     * @param invariants   the shared constraints, restated as constraints
     * @param derivedFrom  the playbook section keys this was derived from, so a client questioning
     *                     a direction can be shown the answer it came from
     */
    public record PurposeDerivation(
        String summary,
        String tension,
        String archetype,
        String tonalRegister,
        String visualTerritory,
        List<String> retains,
        List<String> releases,
        String audienceEmphasis,
        String bet,
        String risk,
        List<String> invariants,
        List<String> derivedFrom
    ) {
        public PurposeDerivation {
            retains = retains == null ? List.of() : List.copyOf(retains);
            releases = releases == null ? List.of() : List.copyOf(releases);
            invariants = invariants == null ? List.of() : List.copyOf(invariants);
            derivedFrom = derivedFrom == null ? List.of() : List.copyOf(derivedFrom);
        }
    }

    public enum CreativeDirection {
        ANCHORED,    // authentic to existing brand equity, evolutionary
        EVOLVED,     // builds on foundations but pushes forward
        DISRUPTIVE   // challenges category conventions, bold repositioning
    }
}
