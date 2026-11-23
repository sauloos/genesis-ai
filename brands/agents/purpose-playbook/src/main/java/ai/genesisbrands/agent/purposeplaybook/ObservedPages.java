package ai.genesisbrands.agent.purposeplaybook;

import ai.genesisbrands.platform.web.WebsiteReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads the pages this engagement named, so the playbook can comment on what is actually there.
 *
 * <p>The rubric makes this non-optional: "a competitor is critiqued on visual or content
 * specifics with no page actually read" is a rejection trigger, and
 * {@code purpose-playbook-structure.yaml} says the competitor evaluation must include first-hand
 * observation and must never be fabricated from the name alone. So the alternative to reading the
 * pages is not a slightly weaker document — it is a document that gets rejected, or worse, one
 * that passes while inventing what a competitor's homepage looks like.
 *
 * <p>Every read goes through the core {@link WebsiteReader}, which refuses private and loopback
 * addresses and caps what it will pull down. A client types these URLs into a questionnaire, so
 * they are untrusted input in the SSRF sense; fetching them with a bare HTTP client would make
 * the questionnaire a request forgery surface.
 *
 * <p>A page that cannot be read is recorded as unread rather than skipped silently, because
 * "the site was down" and "no URL was given" lead the writing to different places: the first is
 * a fact about the competitor worth a line, the second is a gap for the confidence map.
 */
@Component
public class ObservedPages {

    private static final Logger log = LoggerFactory.getLogger(ObservedPages.class);

    /** Enough for the client's own site, five competitors and three admired brands. */
    static final int MAX_PAGES = 9;
    private static final int TEXT_CHARS = 1200;
    private static final int HEADINGS = 6;
    private static final int COLOURS = 6;
    private static final int FONTS = 4;

    private static final Pattern URL_SIGNAL = Pattern.compile(
        "setup\\.website|best\\.competitor\\[\\d+]\\.url|look\\.admired\\[\\d+]\\.url");

    private final WebsiteReader reader;

    public ObservedPages() {
        this(new WebsiteReader());
    }

    ObservedPages(WebsiteReader reader) {
        this.reader = reader;
    }

    /**
     * @param signalKey the signal the URL came from, so the writing knows whose page this is
     * @param rendered  the observation, or why there is none
     * @param read      false when the page could not be fetched — the prose must not then claim
     *                  to have seen it
     */
    public record Observation(String signalKey, String url, String rendered, boolean read) {}

    public List<Observation> readAll(Map<String, Object> signals) {
        List<Observation> observations = new ArrayList<>();
        for (Map.Entry<String, Object> entry : urlSignals(signals).entrySet()) {
            if (observations.size() >= MAX_PAGES) {
                log.info("Purpose playbook: stopping at {} page reads", MAX_PAGES);
                break;
            }
            observations.add(read(entry.getKey(), String.valueOf(entry.getValue()).strip()));
        }
        return observations;
    }

    private Map<String, Object> urlSignals(Map<String, Object> signals) {
        Map<String, Object> urls = new LinkedHashMap<>();
        signals.forEach((key, value) -> {
            if (URL_SIGNAL.matcher(key).matches() && value instanceof String text && !text.isBlank()) {
                urls.put(key, text);
            }
        });
        return urls;
    }

    private Observation read(String signalKey, String url) {
        try {
            return new Observation(signalKey, url, render(reader.read(url)), true);
        } catch (WebsiteReader.Unreadable e) {
            return new Observation(signalKey, url, "could not be read — " + e.getMessage(), false);
        } catch (RuntimeException e) {
            log.warn("Purpose playbook: page read failed for {} ({}): {}", signalKey, url, e.toString());
            return new Observation(signalKey, url, "could not be read — " + e.getMessage(), false);
        }
    }

    private String render(WebsiteReader.Page page) {
        StringBuilder sb = new StringBuilder();
        sb.append("resolved to ").append(page.url()).append("\n");
        if (page.title() != null && !page.title().isBlank()) {
            sb.append("title: ").append(page.title()).append("\n");
        }
        if (page.description() != null && !page.description().isBlank()) {
            sb.append("description: ").append(page.description()).append("\n");
        }
        if (page.headings() != null && !page.headings().isEmpty()) {
            sb.append("headings: ")
                .append(String.join(" | ", page.headings().subList(0, Math.min(HEADINGS, page.headings().size()))))
                .append("\n");
        }
        if (page.colours() != null && !page.colours().isEmpty()) {
            sb.append("colours its CSS uses most: ").append(top(page.colours())).append("\n");
        }
        if (page.fonts() != null && !page.fonts().isEmpty()) {
            sb.append("fonts: ")
                .append(String.join(", ", page.fonts().subList(0, Math.min(FONTS, page.fonts().size()))))
                .append("\n");
        }
        if (page.text() != null && !page.text().isBlank()) {
            sb.append("page text: ").append(WebsiteReader.clip(page.text(), TEXT_CHARS)).append("\n");
        }
        return sb.toString();
    }

    private String top(Map<String, Integer> colours) {
        return colours.keySet().stream().limit(COLOURS).reduce((a, b) -> a + ", " + b).orElse("");
    }
}
