package ai.genesisbrands.agent.logo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;

/**
 * Produces whiteout and mono colour variants of an SVG logo mark by walking
 * the XML DOM and replacing every non-transparent fill/stroke/stop-color with
 * the target colour. Falls back to the original markup silently if parsing
 * fails — the PDF template applies CSS filters as a secondary safety net.
 *
 * Only applicable to SVG logos; DALLE PNGs are returned unchanged (the
 * template's CSS filters handle the visual treatment at render time).
 */
@Service
public class LogoVariantService {

    private static final Logger log = LoggerFactory.getLogger(LogoVariantService.class);

    /** Recolour all fills/strokes to white — for use on coloured backgrounds. */
    public String whiteout(String svgMarkup) {
        return recolor(svgMarkup, "#ffffff");
    }

    /**
     * Recolour all fills/strokes to a single brand colour — for single-colour
     * print, embossing, or reversed-out use cases.
     */
    public String mono(String svgMarkup, String brandColor) {
        return recolor(svgMarkup, brandColor);
    }

    // ── Core recolouring ─────────────────────────────────────────────────────

    public String recolor(String svgMarkup, String targetColor) {
        if (svgMarkup == null || svgMarkup.isBlank()) return svgMarkup;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder()
                .parse(new InputSource(new StringReader(svgMarkup)));
            recolorNode(doc.getDocumentElement(), targetColor);

            Transformer tf = TransformerFactory.newInstance().newTransformer();
            tf.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter out = new StringWriter();
            tf.transform(new DOMSource(doc), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            log.debug("SVG recolor failed, using original markup: {}", e.getMessage());
            return svgMarkup;
        }
    }

    private void recolorNode(Node node, String color) {
        if (node.getNodeType() == Node.ELEMENT_NODE) {
            Element el = (Element) node;

            String fill = el.getAttribute("fill");
            if (!fill.isEmpty() && !isTransparent(fill)) {
                el.setAttribute("fill", color);
            }

            String stroke = el.getAttribute("stroke");
            if (!stroke.isEmpty() && !isTransparent(stroke)) {
                el.setAttribute("stroke", color);
            }

            // Gradient stops
            String stopColor = el.getAttribute("stop-color");
            if (!stopColor.isEmpty()) {
                el.setAttribute("stop-color", color);
            }

            // Inline style — handles fill:; stroke:; stop-color:
            String style = el.getAttribute("style");
            if (!style.isEmpty()) {
                el.setAttribute("style", recolorStyle(style, color));
            }
        }

        NodeList children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            recolorNode(children.item(i), color);
        }
    }

    private boolean isTransparent(String value) {
        String v = value.trim();
        return v.equalsIgnoreCase("none")
            || v.equalsIgnoreCase("transparent")
            // fully-transparent rgba
            || v.matches("rgba?\\(.*,\\s*0\\s*\\)");
    }

    private String recolorStyle(String style, String color) {
        // Replace fill/stroke values but not `fill: none` or `fill: transparent`
        style = style.replaceAll(
            "(?i)(fill)\\s*:\\s*(?!none|transparent)(rgba?\\([^)]+\\)|#[0-9a-fA-F]{3,8}|[a-zA-Z]+)[^;]*",
            "fill:" + color);
        style = style.replaceAll(
            "(?i)(stroke)\\s*:\\s*(?!none|transparent)(rgba?\\([^)]+\\)|#[0-9a-fA-F]{3,8}|[a-zA-Z]+)[^;]*",
            "stroke:" + color);
        style = style.replaceAll(
            "(?i)(stop-color)\\s*:\\s*[^;]+",
            "stop-color:" + color);
        return style;
    }
}
