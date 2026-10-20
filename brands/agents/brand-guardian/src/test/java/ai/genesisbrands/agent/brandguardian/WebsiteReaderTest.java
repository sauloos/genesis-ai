package ai.genesisbrands.agent.brandguardian;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebsiteReaderTest {

    private static InetAddress ip(String literal) throws UnknownHostException {
        return InetAddress.getByName(literal);
    }

    /** Hosts resolve from a table; pages are served from a table; every request is kept. */
    static final class Web {
        final Map<String, String> dns = new HashMap<>();
        final Map<String, WebsiteReader.Fetched> pages = new HashMap<>();
        final List<URI> requested = new ArrayList<>();

        Web host(String host, String ip) {
            dns.put(host, ip);
            return this;
        }

        Web page(String url, int status, String location, String type, String body) {
            pages.put(url, new WebsiteReader.Fetched(status, location, type, body.getBytes(StandardCharsets.UTF_8)));
            return this;
        }

        WebsiteReader reader() {
            return new WebsiteReader(host -> {
                String a = dns.get(host);
                if (a == null) throw new UnknownHostException(host);
                return new InetAddress[] {InetAddress.getByName(a)};
            }, uri -> {
                requested.add(uri);
                WebsiteReader.Fetched f = pages.get(uri.toString());
                return f == null ? new WebsiteReader.Fetched(404, null, "text/html", new byte[0]) : f;
            });
        }
    }

    @Test
    void blocksPrivateLoopbackMetadataAndCarrierNatAddresses() throws Exception {
        for (String a : List.of("127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1",
                "0.0.0.0", "::1", "fd00::1", "fe80::1", "::ffff:10.0.0.1", "224.0.0.1")) {
            assertTrue(WebsiteReader.blocked(ip(a)), a);
        }
        for (String a : List.of("8.8.8.8", "151.101.1.69", "2606:4700::1111", "100.128.0.1")) {
            assertFalse(WebsiteReader.blocked(ip(a)), a);
        }
    }

    @Test
    void acceptsOnlyWebAddressesOnStandardPorts() throws Exception {
        assertEquals("https://example.com/", WebsiteReader.publicUri("example.com").toString());
        assertEquals("http://example.com:80/a", WebsiteReader.publicUri(" http://example.com:80/a ").toString());
        for (String bad : List.of("", "ftp://example.com", "file:///etc/passwd", "https://example.com:8080/",
                "https://user:pw@example.com/", "javascript:alert(1)")) {
            assertThrows(WebsiteReader.Unreadable.class, () -> WebsiteReader.publicUri(bad), bad);
        }
    }

    @Test
    void refusesASiteThatResolvesToAPrivateAddress() {
        Web web = new Web().host("intranet.example", "10.0.0.5");
        WebsiteReader.Unreadable e = assertThrows(WebsiteReader.Unreadable.class, () -> web.reader().read("https://intranet.example/"));
        assertTrue(e.getMessage().contains("not a public website"));
        assertTrue(web.requested.isEmpty());
    }

    @Test
    void rechecksEveryRedirectHop() {
        Web web = new Web().host("shop.example", "93.184.216.34").host("metadata.example", "169.254.169.254")
            .page("https://shop.example/", 302, "http://metadata.example/latest/meta-data/", "text/html", "");
        assertThrows(WebsiteReader.Unreadable.class, () -> web.reader().read("https://shop.example/"));
        assertEquals(1, web.requested.size());
    }

    @Test
    void stopsAfterTooManyRedirects() {
        Web web = new Web().host("loop.example", "93.184.216.34")
            .page("https://loop.example/a", 301, "/b", "text/html", "")
            .page("https://loop.example/b", 301, "/a", "text/html", "");
        WebsiteReader.Unreadable e = assertThrows(WebsiteReader.Unreadable.class, () -> web.reader().read("https://loop.example/a"));
        assertTrue(e.getMessage().contains("too many"));
    }

    @Test
    void readsWordsColoursAndFontsFromThePageAndItsStylesheet() throws Exception {
        Web web = new Web().host("northwind.example", "93.184.216.34")
            .page("https://northwind.example/", 200, null, "text/html; charset=utf-8", """
                <html><head><title>Northwind Coffee</title>
                <meta name="description" content="Light roasts that taste of the farm.">
                <link rel="stylesheet" href="/site.css">
                <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Playfair+Display:wght@700&display=swap">
                <style>h1 { color: #6B3E26; font-family: "Playfair Display", serif } #nav a { color:#fff }</style></head>
                <body><h1>The best coffee in town</h1><h2>Our farms</h2><p style="color: rgb(255, 122, 0)">Order now</p>
                <script>var secret = 1;</script></body></html>""")
            .page("https://northwind.example/site.css", 200, null, "text/css", """
                body { font-family: Arial, sans-serif; background:#f3e3c3 }
                .btn { background: #ff7a00; } .btn:hover { background: #FF7A00 }""");
        WebsiteReader.Page page = web.reader().read("northwind.example");
        assertEquals("Northwind Coffee", page.title());
        assertEquals("Light roasts that taste of the farm.", page.description());
        assertEquals(List.of("The best coffee in town", "Our farms"), page.headings());
        assertTrue(page.text().contains("Order now"));
        assertFalse(page.text().contains("secret"));
        assertEquals(3, page.colours().get("#ff7a00"));
        assertEquals(1, page.colours().get("#6b3e26"));
        assertEquals(1, page.colours().get("#ffffff"));
        assertEquals(1, page.colours().get("#f3e3c3"));
        assertEquals(List.of("Playfair Display", "Arial"), page.fonts());
    }

    @Test
    void refusesAnythingThatIsNotAWebPage() {
        Web web = new Web().host("files.example", "93.184.216.34")
            .page("https://files.example/a.pdf", 200, null, "application/pdf", "%PDF");
        WebsiteReader.Unreadable e = assertThrows(WebsiteReader.Unreadable.class, () -> web.reader().read("https://files.example/a.pdf"));
        assertTrue(e.getMessage().contains("not a web page"));
    }
}
