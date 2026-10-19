package ai.genesisbrands.agent.content;

import ai.genesisbrands.usage.UsageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.openai.OpenAiImageOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * Renders a post's image briefs with the app's OpenAI image model at medium quality, metered
 * to the run through the thread's usage context. A failed image returns null: the post still
 * reaches the inbox, just without that frame.
 */
@Component
public class ContentImages {

    private static final Logger log = LoggerFactory.getLogger(ContentImages.class);
    static final String MODEL = "gpt-image-1";
    /** The usage ledger prices medium quality under its own key. */
    static final String METERED_AS = "gpt-image-1-medium";

    private final ObjectProvider<ImageModel> imageModel;
    private final UsageService usageService;

    public ContentImages(ObjectProvider<ImageModel> imageModel, UsageService usageService) {
        this.imageModel = imageModel;
        this.usageService = usageService;
    }

    public boolean available() {
        return imageModel.getIfAvailable() != null;
    }

    /** @param size {@code 1024x1024}, or {@code 1024x1536} for portrait formats */
    public byte[] render(String brief, String size) {
        ImageModel model = imageModel.getIfAvailable();
        if (model == null) return null;
        try {
            OpenAiImageOptions options = OpenAiImageOptions.builder()
                .model(MODEL)
                .quality("medium")
                .N(1)
                .build();
            options.setSize(size);
            ImageResponse response = model.call(new ImagePrompt(brief, options));
            String b64 = response.getResult().getOutput().getB64Json();
            usageService.recordImage(METERED_AS, 1);
            return b64 == null ? null : Base64.getDecoder().decode(b64);
        } catch (Exception e) {
            log.warn("Content image failed, keeping the post without it: {}", e.getMessage());
            return null;
        }
    }
}
