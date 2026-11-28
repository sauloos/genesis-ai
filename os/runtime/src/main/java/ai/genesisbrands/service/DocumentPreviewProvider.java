package ai.genesisbrands.service;

import java.util.List;
import java.util.Optional;

/**
 * What a tenant supplies so a client can look inside a document it has not (or has not yet)
 * unlocked, without ever receiving the document itself. Core ({@code os/previews}) owns the
 * mechanics: signed expiring page links, per-viewer watermarks, blurring, rate limits and the
 * audit trail. The tenant owns the content behind them: which documents exist, how they are
 * rendered to page images, and which pages are shown, blurred or withheld.
 *
 * <p>The security rule every implementation must keep: {@link #find} returns a document only
 * when the signed-in client may preview it, and it is called again on every page request.
 */
public interface DocumentPreviewProvider {

    /** How one page is treated in a preview. */
    enum Access {
        /** Served in full, with the viewer's watermark. */
        SHOW,
        /** Served heavily blurred, so its layout shows and its words do not. */
        BLUR,
        /** Never served; the viewer is told what it holds instead. */
        WITHHOLD
    }

    /** A previewable document, listed for one client. {@code contentVersion} changes whenever its content does. */
    record Document(String workItemId, String variantKey, String variantLabel, String title, String contentVersion) {}

    /** One rendered page image (JPEG) and the chapter it belongs to; the base for every rendition. */
    record RenderedPage(String chapter, byte[] image) {}

    /** A page's place in the preview sequence, as the viewer is told about it. */
    record PageSpec(int index, String chapter, Access access, String label, String description) {}

    /** The documents this client may preview. Never another client's. */
    List<Document> listPreviewable(String clientUserId);

    /** The one document, only if this client may preview it. */
    Optional<Document> find(String workItemId, String variantKey, String clientUserId);

    /**
     * Renders every page of a document to an image. Slow; called once per content version and
     * cached by core. Images must hold nothing the client has not been allowed to see (the
     * tenant redacts exact colour specifications and the like at this stage).
     */
    List<RenderedPage> renderPages(Document document, String clientUserId);

    /**
     * Decides, for pages with the given chapters in order, what each shows and how it is
     * described. Withheld pages must carry a description of what they hold.
     */
    List<PageSpec> curate(Document document, List<String> chapters);
}
