package ai.genesisbrands.agent.brandbook;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A CSS comment closed with the HTML {@code -->} instead of {@code *}{@code /} runs on to the
 * next comment and silently drops every rule in between. Ember lost its whole Brand
 * Application styling that way.
 */
class BrandBookTemplateCssTest {

    private static final Pattern STYLE = Pattern.compile("<style[^>]*>(.*?)</style>", Pattern.DOTALL);

    @Test
    void noStylesheetCommentSwallowsRules() throws IOException {
        Resource[] templates = new PathMatchingResourcePatternResolver()
            .getResources("classpath*:templates/brand-book/*.html");
        assertThat(templates).isNotEmpty();

        List<String> problems = new ArrayList<>();
        for (Resource template : templates) {
            String html = template.getContentAsString(StandardCharsets.UTF_8);
            Matcher style = STYLE.matcher(html);
            while (style.find()) {
                String css = style.group(1);
                int from = 0;
                while ((from = css.indexOf("/*", from)) >= 0) {
                    int end = css.indexOf("*/", from + 2);
                    String comment = end < 0 ? css.substring(from) : css.substring(from, end);
                    if (end < 0 || comment.contains("{") || comment.contains("-->")) {
                        int line = html.substring(0, style.start(1) + from).split("\n", -1).length;
                        problems.add(template.getFilename() + ":" + line);
                    }
                    if (end < 0) break;
                    from = end + 2;
                }
            }
        }
        assertThat(problems).as("CSS comments that swallow rules").isEmpty();
    }
}
