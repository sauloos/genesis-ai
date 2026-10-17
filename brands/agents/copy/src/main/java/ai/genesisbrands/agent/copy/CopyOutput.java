package ai.genesisbrands.agent.copy;

import java.util.List;

public record CopyOutput(
    String engagementId,
    String tagline,
    String missionStatement,
    String brandStory,
    String elevatorPitch,
    ToneGuide toneGuide,
    VisionPillars vision,
    List<BrandValue> values,
    List<VocabularyEntry> vocabulary,
    String reasoning,
    int iteration
) {
    public record ToneGuide(
        List<String> principles,
        List<ToneExample> examples
    ) {}

    public record ToneExample(
        String principle,
        String doThis,
        String notThis
    ) {}

    public record VisionPillars(
        String growth,
        String environment,
        String philanthropy,
        String fame,
        String productivity,
        String location
    ) {}

    public enum IconCategory {
        ACHIEVEMENT, PERSON, SOCIAL, PLAYFUL, FORMAL, NATURE, STRUCTURE, INNOVATION
    }

    public record BrandValue(
        String name,
        String description,
        IconCategory iconCategory
    ) {}

    public record VocabularyEntry(
        String word,
        String meaning
    ) {}
}
