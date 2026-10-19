package ai.genesisbrands.publishing;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublishingRulesTest {

    @Test
    void returnPathsStayOnThisSite() {
        assertEquals("/live/dashboard?tab=1", PublishingService.safeReturn("/live/dashboard?tab=1"));
        assertEquals("/", PublishingService.safeReturn("https://evil.test/"));
        assertEquals("/", PublishingService.safeReturn("//evil.test/"));
        assertEquals("/", PublishingService.safeReturn("/\\evil.test"));
        assertEquals("/", PublishingService.safeReturn("/a\r\nSet-Cookie: x"));
        assertEquals("/", PublishingService.safeReturn(null));
    }

    @Test
    void retriesBackOffQuadratically() {
        assertEquals(Duration.ofMinutes(5), PublishingService.backoff(1));
        assertEquals(Duration.ofMinutes(20), PublishingService.backoff(2));
    }

    @Test
    void imagesBecomeJpegAndJpegsAreKept() throws Exception {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        assertFalse(JpegImages.isJpeg(png.toByteArray()));
        byte[] jpeg = JpegImages.toJpeg(png.toByteArray());
        assertTrue(JpegImages.isJpeg(jpeg));
        assertArrayEquals(jpeg, JpegImages.toJpeg(jpeg));
    }
}
