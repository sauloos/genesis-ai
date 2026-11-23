package ai.genesisbrands.agent.purposeplaybook;

import ai.genesisbrands.platform.web.WebsiteReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Page reading is not a nice-to-have here — the rubric rejects a competitor critique written from
 * the name alone — so these tests pin both halves of the contract: which signals carry a URL, and
 * that a page which could not be read is recorded as unread rather than quietly dropped.
 *
 * <p>Goes through the real {@link WebsiteReader} with a stub resolver and transport rather than a
 * stub reader, so the SSRF guard and the HTML parsing are exercised as they will run.
 */
class ObservedPagesTest {

    private static final String HTML = """
        <!doctype html>
        <html><head>
          <title>Northbound Logistics</title>
          <meta name="description" content="Freight handover, without the paper.">
          <style>body { color: #112233; font-family: Georgia, serif; }</style>
        </head><body>
          <h1>Freight handover, without the paper</h1>
          <h2>Built for the people on the bay</h2>
          <p>We shorten the gap between a warehouse and a driver.</p>
          <script>console.log('ignored');</script>
        </body></html>
        """;

    @Test
    void readsOnlyTheSignalsThatCarryAUrl() {
        Map<String, Object> signals = new LinkedHashMap<>();
        signals.put("setup.website", "northbound.example.com");
        signals.put("setup.oneLiner", "We shorten the handover");
        signals.put("best.competitor[0].url", "https://rival.example.com");
        signals.put("best.competitor[0].name", "Rival Freight");
        signals.put("look.admired[1].url", "https://admired.example.com/brand");
        signals.put("look.mustKeep", "the van blue");

        List<ObservedPages.Observation> observations = pages(HTML).readAll(signals);

        assertThat(observations).extracting(ObservedPages.Observation::signalKey)
            .containsExactly("setup.website", "best.competitor[0].url", "look.admired[1].url");
    }

    @Test
    void ignoresABlankOrNonTextualUrl() {
        Map<String, Object> signals = new LinkedHashMap<>();
        signals.put("setup.website", "   ");
        signals.put("best.competitor[0].url", 42);
        signals.put("look.admired[0].url", "https://admired.example.com");

        assertThat(pages(HTML).readAll(signals))
            .extracting(ObservedPages.Observation::signalKey)
            .containsExactly("look.admired[0].url");
    }

    @Test
    void recordsWhatIsActuallyOnThePage() {
        List<ObservedPages.Observation> observations =
            pages(HTML).readAll(Map.of("setup.website", "northbound.example.com"));

        assertThat(observations).hasSize(1);
        ObservedPages.Observation observation = observations.get(0);

        assertThat(observation.read()).isTrue();
        assertThat(observation.url()).isEqualTo("northbound.example.com");
        assertThat(observation.rendered())
            .contains("resolved to https://northbound.example.com/")
            .contains("title: Northbound Logistics")
            .contains("description: Freight handover, without the paper.")
            .contains("headings: Freight handover, without the paper | Built for the people on the bay")
            .contains("#112233")
            .contains("fonts: Georgia")
            .contains("page text: ")
            .contains("shorten the gap between a warehouse and a driver")
            .doesNotContain("console.log");
    }

    @Test
    void recordsAPageItCouldNotReadAsUnreadWithTheReason() {
        ObservedPages unresolvable = new ObservedPages(new WebsiteReader(
            host -> {
                throw new UnknownHostException(host);
            },
            uri -> {
                throw new AssertionError("must not fetch a host that does not resolve");
            }));

        List<ObservedPages.Observation> observations =
            unresolvable.readAll(Map.of("best.competitor[0].url", "gone.example.com"));

        assertThat(observations).hasSize(1);
        assertThat(observations.get(0).read()).isFalse();
        assertThat(observations.get(0).rendered())
            .startsWith("could not be read — ")
            .contains("gone.example.com")
            .contains("does not exist");
    }

    @Test
    void refusesAPrivateAddressWithoutFetchingIt() {
        ObservedPages internal = new ObservedPages(new WebsiteReader(
            host -> new InetAddress[] {InetAddress.getByAddress(new byte[] {10, 0, 0, 7})},
            uri -> {
                throw new AssertionError("must not fetch a private address");
            }));

        ObservedPages.Observation observation =
            internal.readAll(Map.of("setup.website", "intranet.example.com")).get(0);

        assertThat(observation.read()).isFalse();
        assertThat(observation.rendered()).contains("not a public website");
    }

    @Test
    void recordsASiteThatAnsweredWithAnErrorAsUnread() {
        ObservedPages broken = new ObservedPages(new WebsiteReader(
            publicHost(), uri -> new WebsiteReader.Fetched(503, null, "text/html", new byte[0])));

        ObservedPages.Observation observation =
            broken.readAll(Map.of("setup.website", "down.example.com")).get(0);

        assertThat(observation.read()).isFalse();
        assertThat(observation.rendered()).contains("status 503");
    }

    @Test
    void stopsAtTheReadBudget() {
        Map<String, Object> signals = new LinkedHashMap<>();
        signals.put("setup.website", "northbound.example.com");
        for (int i = 0; i < 12; i++) {
            signals.put("best.competitor[" + i + "].url", "https://rival" + i + ".example.com");
        }

        assertThat(pages(HTML).readAll(signals)).hasSize(ObservedPages.MAX_PAGES);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private static ObservedPages pages(String html) {
        return new ObservedPages(new WebsiteReader(publicHost(),
            uri -> served(uri, html)));
    }

    private static WebsiteReader.Fetched served(URI uri, String html) throws IOException {
        if (uri.getPath() != null && uri.getPath().endsWith(".css")) {
            throw new IOException("no stylesheets in this fixture");
        }
        return new WebsiteReader.Fetched(200, null, "text/html; charset=utf-8",
            html.getBytes(StandardCharsets.UTF_8));
    }

    /** A routable public address, so the guard lets the stub transport run. */
    private static WebsiteReader.Resolver publicHost() {
        return host -> new InetAddress[] {
            InetAddress.getByAddress(host, new byte[] {(byte) 93, (byte) 184, (byte) 216, (byte) 34})};
    }
}
