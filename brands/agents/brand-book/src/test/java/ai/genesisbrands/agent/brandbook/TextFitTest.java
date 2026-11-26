package ai.genesisbrands.agent.brandbook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextFitTest {

    @Test
    void splitsSentencesButNotDecimalsOrAbbreviations() {
        assertThat(TextFit.sentences("Roasted at 2.5 kg a batch. E.g. our Brazil lot. Done!"))
            .containsExactly("Roasted at 2.5 kg a batch.", "E.g. our Brazil lot.", "Done!");
    }

    @Test
    void keepsWholeSentencesThatFit() {
        String text = "We roast on Mondays. Every bag is dated. Nothing sits on a shelf.";
        assertThat(TextFit.fit(text, 45)).isEqualTo("We roast on Mondays. Every bag is dated.");
        assertThat(TextFit.fit(text, 200)).isEqualTo(text);
    }

    @Test
    void cutsAnOverlongSentenceAtAClause() {
        assertThat(TextFit.fit("We roast every batch by hand, and we date every single bag we send", 35))
            .isEqualTo("We roast every batch by hand…");
    }

    @Test
    void otherwiseCutsAtAWord() {
        assertThat(TextFit.clip("one two three four five six", 12)).isEqualTo("one two…");
        assertThat(TextFit.clip("short", 12)).isEqualTo("short");
    }

    @Test
    void aSnippetStopsOnceItReadsAsAThought() {
        String text = "Coffee, dated. We roast on Mondays and ship on Tuesdays. Nothing sits on a shelf.";
        assertThat(TextFit.snippet(text, 40, 200))
            .isEqualTo("Coffee, dated. We roast on Mondays and ship on Tuesdays.");
    }
}
