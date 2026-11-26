package ai.genesisbrands.agent.brandbook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns the typeface names the Visual Identity agent writes into a Google Fonts stylesheet URL
 * that actually loads, and reports which weights each face really has.
 *
 * <p>The css2 API answers 400 for the whole request when one family or one requested weight
 * doesn't exist. The templates asked for weights 300/400/700 of every heading face and 400/600/700
 * of every body face, and for names such as "Archivo Expanded" or "Inter (Variable)", so a single
 * miss meant no webfont at all and every book fell back to Georgia and Arial (found 2026-11-26).
 * Here each family is probed once per process: does the name exist (else a shorter name, else a
 * known-good fallback), and which of the nine standard weights does it serve.
 */
final class GoogleFonts {

    private static final Logger log = LoggerFactory.getLogger(GoogleFonts.class);

    static final String CSS2 = "https://fonts.googleapis.com/css2";

    /** What a book uses when the agent named a face Google Fonts does not have. */
    static final String FALLBACK_HEADING = "Fraunces";
    static final String FALLBACK_BODY = "Inter";

    /**
     * Words a model appends to a family name that are styles or widths of it, not part of it.
     * Black, Bold and Display are deliberately absent: "Archivo Black" and "Playfair Display" are
     * families of their own.
     */
    private static final Set<String> STYLE_WORDS = Set.of(
        "expanded", "condensed", "extended", "variable", "regular", "italic", "light", "medium",
        "semibold", "semi-bold", "extrabold", "extra-bold", "thin", "heavy", "book", "roman", "vf");

    static final int[] STANDARD_WEIGHTS = {100, 200, 300, 400, 500, 600, 700, 800, 900};

    /** One resolved face: the family name the book shows and loads, and the weights it has. */
    record Face(String family, List<Integer> weights, boolean italic, boolean substituted) {

        /**
         * A light, the regular and a bold, without repeats. Light 300 and Bold 700 when the face
         * has them: a Thin 100 or Black 900 specimen shows an extreme nobody sets text in.
         */
        List<Integer> specimenWeights() {
            if (weights.isEmpty()) return List.of(400);
            int lightest = weights.contains(300) ? 300 : weights.get(0);
            int heaviest = weights.contains(700) ? 700 : weights.get(weights.size() - 1);
            int regular = weights.stream().min((a, b) -> Integer.compare(Math.abs(a - 400), Math.abs(b - 400))).orElse(400);
            TreeSet<Integer> picked = new TreeSet<>(List.of(lightest, regular, heaviest));
            return new ArrayList<>(picked);
        }
    }

    /** Answers the HTTP status of a css2 URL; injectable for tests. Negative = network failure. */
    interface Probe {
        int status(String url);
    }

    private final Probe probe;
    private final Map<String, Face> cache = new ConcurrentHashMap<>();

    GoogleFonts(Probe probe) {
        this.probe = probe;
    }

    static GoogleFonts live() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
        return new GoogleFonts(url -> {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4))
                    // css2 serves woff2 rules to a modern browser user agent; any UA gets a status.
                    .header("User-Agent", "Mozilla/5.0 (brand-book renderer)").GET().build();
                return client.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            } catch (Exception e) {
                return -1;
            }
        });
    }

    /** "Inter (Variable)" → "Inter"; "'Söhne', sans-serif" → "Söhne"; "Lora / Georgia" → "Lora". */
    static String sanitise(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("\\(.*?\\)", " ");
        int cut = indexOfAny(s, "/", ",", ";", "|");
        if (cut >= 0) s = s.substring(0, cut);
        s = s.replace("\"", "").replace("'", "").replace("‘", "").replace("’", "");
        return s.replaceAll("\\s+", " ").strip();
    }

    private static int indexOfAny(String s, String... needles) {
        int best = -1;
        for (String n : needles) {
            int i = s.indexOf(n);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    Face resolve(String requested, String fallbackFamily) {
        String clean = sanitise(requested);
        String key = clean.toLowerCase(Locale.ROOT) + "|" + fallbackFamily;
        Face cached = cache.get(key);
        if (cached != null) return cached;

        List<String> candidates = new ArrayList<>();
        if (!clean.isEmpty()) {
            candidates.add(clean);
            List<String> words = new ArrayList<>(Arrays.asList(clean.split(" ")));
            while (words.size() > 1 && STYLE_WORDS.contains(words.get(words.size() - 1).toLowerCase(Locale.ROOT))) {
                words.remove(words.size() - 1);
                candidates.add(String.join(" ", words));
            }
        }
        boolean networkDown = false;
        for (int i = 0; i < candidates.size(); i++) {
            String family = candidates.get(i);
            int status = probe.status(familyUrl(family, null));
            if (status < 0) { networkDown = true; break; }
            if (status == 200) {
                Face face = new Face(family, weightsOf(family), probe.status(familyUrl(family, "ital,wght@1,400")) == 200,
                    i > 0);
                cache.put(key, face);
                if (i > 0) log.info("Brand book font '{}' is not a Google Fonts family; using '{}'", requested, family);
                return face;
            }
        }
        if (networkDown) {
            // Can't check: ask for the name as written with the two weights nearly every family
            // has. Not cached, so the next render checks again.
            log.warn("Google Fonts unreachable; loading '{}' unchecked", clean);
            return new Face(clean.isEmpty() ? fallbackFamily : clean, List.of(400, 700), false, false);
        }
        log.warn("Brand book font '{}' is not on Google Fonts; substituting '{}'", requested, fallbackFamily);
        Face fb = clean.equalsIgnoreCase(fallbackFamily)
            ? new Face(fallbackFamily, List.of(400, 700), false, true)
            : resolve(fallbackFamily, fallbackFamily);
        fb = new Face(fb.family(), fb.weights(), fb.italic(), true);
        cache.put(key, fb);
        return fb;
    }

    private List<Integer> weightsOf(String family) {
        List<Integer> out = new ArrayList<>();
        for (int w : STANDARD_WEIGHTS) {
            if (probe.status(familyUrl(family, "wght@" + w)) == 200) out.add(w);
        }
        if (out.isEmpty()) out.add(400);
        return out;
    }

    static String familyUrl(String family, String axes) {
        return CSS2 + "?family=" + familyParam(family) + (axes != null ? ":" + axes : "") + "&display=swap";
    }

    static String familyParam(String family) {
        return URLEncoder.encode(family, StandardCharsets.UTF_8);
    }

    /**
     * The stylesheet href for both faces with only the weights each has. HTML-escaped ampersands,
     * since the value lands inside an attribute.
     */
    static String stylesheetHref(Face heading, Face body) {
        StringBuilder sb = new StringBuilder(CSS2).append('?');
        sb.append("family=").append(familyAxes(heading));
        if (!body.family().equalsIgnoreCase(heading.family())) {
            sb.append("&amp;family=").append(familyAxes(body));
        }
        return sb.append("&amp;display=swap").toString();
    }

    private static String familyAxes(Face f) {
        // css2 wants tuples sorted by ital then weight.
        StringBuilder t = new StringBuilder();
        for (int w : f.weights()) {
            if (t.length() > 0) t.append(';');
            t.append("0,").append(w);
        }
        if (f.italic()) t.append(";1,400");
        return familyParam(f.family()) + ":ital,wght@" + t;
    }

    static String weightLabel(int w) {
        return switch (w) {
            case 100 -> "Thin";
            case 200 -> "ExtraLight";
            case 300 -> "Light";
            case 400 -> "Regular";
            case 500 -> "Medium";
            case 600 -> "SemiBold";
            case 700 -> "Bold";
            case 800 -> "ExtraBold";
            case 900 -> "Black";
            default -> String.valueOf(w);
        };
    }
}
