package ai.genesisbrands.agent;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Widget stylesheets style themselves only through --theme-* tokens, so the active theme
 * decides the look: no hex, rgb()/rgba()/hsl() or named colour literals. Defaults live in
 * /widgets/tokens.css (the one file allowed literals); tints use color-mix() over a token.
 * This app adopts every widget module, so its classpath sees every widget stylesheet.
 */
class WidgetCssTokensTest {

    private static final Pattern COLOUR_LITERAL = Pattern.compile(
        "#[0-9a-fA-F]{3,8}\\b|\\b(?:rgba?|hsla?)\\s*\\("
        + "|:\\s*(?:white|black|red|green|blue|gray|grey|orange|yellow|purple|pink|silver)\\b");

    @Test
    void widgetStylesheetsUseOnlyThemeTokens() throws Exception {
        Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath*:static/widgets/**/*.css");
        assertThat(files).isNotEmpty();
        List<String> violations = new ArrayList<>();
        for (Resource r : files) {
            String url = r.getURL().toString();
            if (url.endsWith("/static/widgets/tokens.css")) continue;
            String css = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", "");
            Matcher m = COLOUR_LITERAL.matcher(css);
            while (m.find()) {
                violations.add(url.substring(url.lastIndexOf("static/widgets/")) + ": " + m.group());
            }
        }
        assertThat(violations).isEmpty();
    }
}
