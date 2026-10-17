package ai.genesisbrands.agent.logo;

import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;

/**
 * Validates that svgMarkup from the SVG Logo Agent is actual renderable SVG markup,
 * not a description. Rejects output that cannot be XML-parsed or that does not start
 * with an &lt;svg&gt; root element — triggering the existing 2-attempt retry loop in LogoAgent.
 */
class SvgValidator {

    private SvgValidator() {}

    static void validate(String svgMarkup) {
        if (svgMarkup == null || svgMarkup.isBlank()) {
            throw new IllegalStateException("svgMarkup is null or empty");
        }
        String trimmed = svgMarkup.strip();
        if (!trimmed.startsWith("<svg") && !trimmed.startsWith("<?xml")) {
            throw new IllegalStateException(
                "svgMarkup does not start with <svg — model produced a description, not markup. First 120 chars: "
                + trimmed.substring(0, Math.min(120, trimmed.length())));
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.newDocumentBuilder().parse(new InputSource(new StringReader(trimmed)));
        } catch (Exception e) {
            throw new IllegalStateException("svgMarkup is not valid XML: " + e.getMessage());
        }
    }
}
