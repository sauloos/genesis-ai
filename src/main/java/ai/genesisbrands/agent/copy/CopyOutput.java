package ai.genesisbrands.agent.copy;

import java.util.List;

public record CopyOutput(
    String engagementId,
    String tagline,
    String missionStatement,
    String brandStory,
    String elevatorPitch,
    ToneGuide toneGuide,
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
}
