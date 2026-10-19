package ai.genesisbrands.standing;

import ai.genesisbrands.platform.AgentCustomOption;
import ai.genesisbrands.platform.AgentCustomOption.OptionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OptionValuesTest {

    private static final AgentCustomOption TONE = new AgentCustomOption("tone", "Tone", OptionType.SELECT, List.of("concise", "detailed"), "concise");
    private static final AgentCustomOption FOCUS = new AgentCustomOption("focus", "Focus", OptionType.MULTI_SELECT, List.of("a", "b", "c"), "a,b");
    private static final AgentCustomOption POSTS = new AgentCustomOption("posts", "Posts per week", OptionType.NUMBER, List.of("1", "7"), "3");
    private static final List<AgentCustomOption> ALL = List.of(TONE, FOCUS, POSTS);

    @Test
    void clientValuesOverrideAdminDefaultsWhichOverrideTheAgent() {
        Map<String, String> out = OptionValues.resolve(ALL, Map.of("tone", "detailed", "posts", "5"), Set.of(),
            Map.of("posts", "2", "unknown", "x"));
        assertEquals(Map.of("tone", "detailed", "focus", "a,b", "posts", "2"), out);
    }

    @Test
    void lockedOptionsIgnoreTheClient() {
        Map<String, String> out = OptionValues.resolve(ALL, Map.of("posts", "5"), Set.of("posts"), Map.of("posts", "7"));
        assertEquals("5", out.get("posts"));
    }

    @Test
    void multiSelectKeepsDeclaredOrderAndRejectsStrangers() {
        assertEquals("a,c", OptionValues.normalize(FOCUS, "c, a ,a"));
        assertThrows(IllegalArgumentException.class, () -> OptionValues.normalize(FOCUS, "a,z"));
    }

    @Test
    void numbersRespectBounds() {
        assertEquals("7", OptionValues.normalize(POSTS, " 7 "));
        assertThrows(IllegalArgumentException.class, () -> OptionValues.normalize(POSTS, "8"));
        assertThrows(IllegalArgumentException.class, () -> OptionValues.normalize(POSTS, "three"));
    }

    @Test
    void selectRejectsUnlistedValues() {
        assertThrows(IllegalArgumentException.class, () -> OptionValues.normalize(TONE, "shouty"));
    }
}
