package ai.genesisbrands.agent.brandbook;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BrandBookPreviewPagesTest {

    @Test
    void pagesTakeTheChapterThatOpenedThemAndTheEndsAreNamedByClass() {
        String html = "<section class=\"page page-cover\">c</section>"
            + "<section data-toc=\"Brand Statement\" class=\"page\">a</section>"
            + "<section class=\"page\">b</section>"
            + "<section data-toc=\"Layout &amp; Grids\" class=\"page\">d</section>"
            + "<section class=\"page page-closing\">e</section>"
            + "<section class=\"page page-back\">f</section>";

        assertThat(BrandBookTemplateRenderer.pageChapters(html)).isEqualTo(List.of(
            "Cover", "Brand Statement", "Brand Statement", "Layout & Grids", "Closing", "Closing"));
    }

    @Test
    void everyRealTemplateTagsEveryPageWithAChapter() throws Exception {
        for (String t : new String[]{"template.html", "template-classic.html", "template-ember.html", "template-meridian.html"}) {
            String html = new String(getClass().getResourceAsStream("/templates/brand-book/" + t).readAllBytes());
            List<String> chapters = BrandBookTemplateRenderer.pageChapters(html);
            assertThat(chapters).as(t).hasSizeGreaterThan(15);
            assertThat(chapters.get(0)).as(t).isEqualTo("Cover");
            assertThat(chapters).as(t).doesNotContain("");
            assertThat(chapters).as(t).contains("Logo System", "Typography", "Closing");
        }
    }
}
