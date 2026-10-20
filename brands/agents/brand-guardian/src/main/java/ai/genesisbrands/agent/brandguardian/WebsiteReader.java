package ai.genesisbrands.agent.brandguardian;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a client's public web page for review: its words, and the colours and fonts its CSS
 * uses. The address comes from the client, so every request, redirect hop and stylesheet is
 * checked to be a public website on a standard port before it is fetched; private, loopback,
 * link-local (cloud metadata) and carrier-NAT addresses are refused.
 *
 * <p>Residual risk: the HTTP client resolves the host again after the check, so a hostile DNS
 * server could answer differently the second time (DNS rebinding). The reader only ever
 * returns text to a model, never to the caller, which limits what such a request could leak.
 */
final class WebsiteReader {

    static final int MAX_BYTES = 2_000_000;
    static final int MAX_REDIRECTS = 3;
    static final int MAX_STYLESHEETS = 3;
    static final int TEXT_CHARS = 6000;
    private static final Pattern HEX = Pattern.compile("(?<=[:\\s,(])#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})(?![0-9a-zA-Z_-])");
    private static final Pattern RGB = Pattern.compile("rgba?\\(\\s*(\\d{1,3})\\s*[, ]\\s*(\\d{1,3})\\s*[, ]\\s*(\\d{1,3})");
    private static final Pattern FONT = Pattern.compile("font-family\\s*:\\s*([^;}{]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GOOGLE_FAMILY = Pattern.compile("family=([^:&]+)");
    private static final Set<String> GENERIC_FONTS = Set.of("serif", "sans-serif", "monospace", "cursive", "fantasy",
        "system-ui", "ui-sans-serif", "ui-serif", "ui-monospace", "inherit", "initial", "unset", "-apple-system",
        "blinkmacsystemfont", "emoji", "math");

    /** Why a page could not be read, in words the client can act on. */
    static final class Unreadable extends Exception {
        Unreadable(String message) {
            super(message);
        }
    }

    interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    /** One request, never following redirects. */
    interface Transport {
        Fetched get(URI uri) throws IOException;
    }

    record Fetched(int status, String location, String contentType, byte[] body) {}

    /** What the review needs from one page. Colours and fonts are ordered by how often they appear. */
    record Page(String url, String title, String description, List<String> headings, String text,
                Map<String, Integer> colours, List<String> fonts) {}

    private final Resolver resolver;
    private final Transport transport;

    WebsiteReader() {
        this(InetAddress::getAllByName, new JdkTransport());
    }

    WebsiteReader(Resolver resolver, Transport transport) {
        this.resolver = resolver;
        this.transport = transport;
    }

    Page read(String address) throws Unreadable {
        URI uri = publicUri(address);
        Fetched page = fetch(uri);
        if (page.contentType() != null && !page.contentType().toLowerCase(Locale.ROOT).contains("html")) {
            throw new Unreadable("it is not a web page (" + page.contentType() + ").");
        }
        Document doc = Jsoup.parse(new String(page.body(), StandardCharsets.UTF_8), uri.toString());
        StringBuilder css = new StringBuilder();
        for (Element style : doc.select("style")) css.append(style.data()).append('\n');
        for (Element styled : doc.select("[style]")) css.append(':').append(styled.attr("style")).append(";\n");
        List<String> fonts = new ArrayList<>();
        int sheets = 0;
        for (Element link : doc.select("link[rel=stylesheet][href]")) {
            String href = link.absUrl("href");
            Matcher google = GOOGLE_FAMILY.matcher(href);
            if (href.contains("fonts.googleapis.com")) {
                while (google.find()) fonts.add(google.group(1).replace('+', ' '));
                continue;
            }
            if (sheets >= MAX_STYLESHEETS || href.isEmpty()) continue;
            sheets++;
            try {
                css.append(new String(fetch(publicUri(href)).body(), StandardCharsets.UTF_8)).append('\n');
            } catch (Unreadable ignored) {
                // A stylesheet we may not fetch only means fewer colours to compare.
            }
        }
        Element theme = doc.selectFirst("meta[name=theme-color]");
        if (theme != null) css.append(':').append(theme.attr("content")).append(";\n");
        fonts.addAll(fonts(css.toString()));

        String description = meta(doc, "meta[name=description]");
        if (description.isEmpty()) description = meta(doc, "meta[property=og:description]");
        Set<String> headings = new LinkedHashSet<>();
        for (Element h : doc.select("h1, h2, h3")) {
            String t = h.text().strip();
            if (!t.isEmpty()) headings.add(clip(t, 120));
            if (headings.size() >= 25) break;
        }
        doc.select("script, style, noscript, svg, template").remove();
        String text = doc.body() == null ? "" : clip(doc.body().text(), TEXT_CHARS);
        List<String> families = new ArrayList<>(new LinkedHashSet<>(fonts));
        return new Page(uri.toString(), clip(doc.title(), 150), clip(description, 300), List.copyOf(headings), text,
            colours(css.toString()), List.copyOf(families.subList(0, Math.min(8, families.size()))));
    }

    /** Fetches a checked address, re-checking every redirect hop. */
    private Fetched fetch(URI uri) throws Unreadable {
        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            requirePublicHost(current);
            Fetched res;
            try {
                res = transport.get(current);
            } catch (IOException e) {
                throw new Unreadable("the site did not answer (" + e.getClass().getSimpleName() + ").");
            }
            if (res.status() >= 300 && res.status() < 400 && res.location() != null) {
                try {
                    current = publicUri(current.resolve(res.location().strip()).toString());
                } catch (IllegalArgumentException e) {
                    throw new Unreadable("the site redirected to an invalid address.");
                }
                continue;
            }
            if (res.status() != 200) throw new Unreadable("the site answered with status " + res.status() + ".");
            return res;
        }
        throw new Unreadable("the site redirected too many times.");
    }

    /** An http(s) address on a standard port, without credentials; a bare domain gets https. */
    static URI publicUri(String address) throws Unreadable {
        String raw = address == null ? "" : address.strip();
        if (raw.isEmpty()) throw new Unreadable("no address was given.");
        if (!raw.contains("://")) raw = "https://" + raw;
        URI uri;
        try {
            uri = new URI(raw).normalize();
        } catch (URISyntaxException e) {
            throw new Unreadable("it is not a valid web address.");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) throw new Unreadable("only http and https addresses can be checked.");
        if (uri.getHost() == null || uri.getHost().isBlank()) throw new Unreadable("it is not a valid web address.");
        if (uri.getRawUserInfo() != null) throw new Unreadable("addresses with a user name or password are not checked.");
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) throw new Unreadable("only standard web ports can be checked.");
        return uri.getRawPath() == null || uri.getRawPath().isEmpty() ? uri.resolve("/") : uri;
    }

    private void requirePublicHost(URI uri) throws Unreadable {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(uri.getHost());
        } catch (UnknownHostException e) {
            throw new Unreadable("the address " + uri.getHost() + " does not exist.");
        }
        if (addresses.length == 0) throw new Unreadable("the address " + uri.getHost() + " does not exist.");
        for (InetAddress a : addresses) {
            if (blocked(a)) throw new Unreadable("it is not a public website.");
        }
    }

    /** Loopback, private, link-local, carrier-NAT, multicast, unspecified and unique-local addresses. */
    static boolean blocked(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff, b1 = b[1] & 0xff;
            return b0 == 0 || (b0 == 100 && (b1 & 0xc0) == 64) || (b0 == 198 && (b1 == 18 || b1 == 19)) || b0 >= 240;
        }
        if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return true;
            boolean mapped = true;
            for (int i = 0; i < 10; i++) mapped &= b[i] == 0;
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try {
                    return blocked(InetAddress.getByAddress(new byte[] {b[12], b[13], b[14], b[15]}));
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Colours in the CSS as lower-case #rrggbb, most used first, at most 12. */
    static Map<String, Integer> colours(String css) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher hex = HEX.matcher(css);
        while (hex.find()) counts.merge(sixDigits(hex.group(1)), 1, Integer::sum);
        Matcher rgb = RGB.matcher(css);
        while (rgb.find()) {
            int r = Integer.parseInt(rgb.group(1)), g = Integer.parseInt(rgb.group(2)), bl = Integer.parseInt(rgb.group(3));
            if (r > 255 || g > 255 || bl > 255) continue;
            counts.merge(String.format("#%02x%02x%02x", r, g, bl), 1, Integer::sum);
        }
        Map<String, Integer> top = new LinkedHashMap<>();
        counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder())).limit(12)
            .forEach(e -> top.put(e.getKey(), e.getValue()));
        return top;
    }

    /** The first named family of each font-family declaration, most used first. */
    static List<String> fonts(String css) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Matcher m = FONT.matcher(css);
        while (m.find()) {
            for (String family : m.group(1).split(",")) {
                String name = family.replace("\"", "").replace("'", "").replace("!important", "").strip();
                if (name.isEmpty() || name.startsWith("var(") || GENERIC_FONTS.contains(name.toLowerCase(Locale.ROOT))) continue;
                counts.merge(name, 1, Integer::sum);
                break;
            }
        }
        return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
            .map(Map.Entry::getKey).toList();
    }

    private static String sixDigits(String hex) {
        String h = hex.toLowerCase(Locale.ROOT);
        if (h.length() == 3) h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
        return "#" + h;
    }

    private static String meta(Document doc, String selector) {
        Element e = doc.selectFirst(selector);
        return e == null ? "" : e.attr("content").strip();
    }

    static String clip(String s, int max) {
        if (s == null) return "";
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    /** The JDK client with redirects off, short timeouts and a capped body. */
    static final class JdkTransport implements Transport {

        private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

        @Override
        public Fetched get(URI uri) throws IOException {
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("User-Agent", "GenesisBrandGuardian/1.0 (+https://www.genesisbrands.ai)")
                .header("Accept", "text/html,text/css;q=0.9,*/*;q=0.5")
                .GET().build();
            try {
                HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream in = res.body()) {
                    byte[] body = in.readNBytes(MAX_BYTES);
                    return new Fetched(res.statusCode(), res.headers().firstValue("location").orElse(null),
                        res.headers().firstValue("content-type").orElse(null), body);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }
    }
}
