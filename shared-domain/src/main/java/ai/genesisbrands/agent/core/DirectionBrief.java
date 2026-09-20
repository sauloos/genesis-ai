package ai.genesisbrands.agent.core;

import java.util.List;

public record DirectionBrief(
    String engagementId,
    BrandContext brand,
    BrandFoundation foundation,
    CreativeDirection direction,
    List<String> trainingInstructions,
    String additionalContext
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

    public enum CreativeDirection {
        ANCHORED,    // authentic to existing brand equity, evolutionary
        EVOLVED,     // builds on foundations but pushes forward
        DISRUPTIVE   // challenges category conventions, bold repositioning
    }
}
