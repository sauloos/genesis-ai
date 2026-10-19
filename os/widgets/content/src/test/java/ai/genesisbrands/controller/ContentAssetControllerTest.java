package ai.genesisbrands.controller;

import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.security.AdminAuthHelper;
import ai.genesisbrands.security.AdminSessionService;
import ai.genesisbrands.service.BlobStorageService;
import ai.genesisbrands.service.PageWidgetService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentAssetControllerTest {

    @Mock private AdminAuthHelper adminAuth;
    @Mock private AdminSessionService adminSession;
    @Mock private PageWidgetService pageWidgetService;
    @Mock private BlobStorageService blobStorageService;
    @Mock private HttpServletRequest req;
    @InjectMocks private ContentAssetController controller;

    @BeforeEach
    void setUp() {
        when(adminAuth.isAdminRequest(req)).thenReturn(true);
    }

    @Test
    void uploadContentImage_storesUnderContentWidgetsBlobPathAndReturnsAssetUrl() throws Exception {
        when(pageWidgetService.get("w1")).thenReturn(new PageWidget());
        MockMultipartFile file = new MockMultipartFile("image", "logo.png", "image/png", "bytes".getBytes());

        ResponseEntity<?> res = controller.uploadContentImage("p1", "w1", file, req);

        String url = (String) ((Map<?, ?>) res.getBody()).get("url");
        assertThat(url).startsWith("/api/assets/content-widgets/w1/").endsWith("_logo.png");
        verify(blobStorageService).upload(anyString(), any(byte[].class));
    }

    @Test
    void uploadContentHtmlFile_storesUnderContentWidgetsBlobPathAndReturnsAssetUrl() throws Exception {
        when(pageWidgetService.get("w1")).thenReturn(new PageWidget());
        MockMultipartFile file = new MockMultipartFile("file", "landing.html", "text/html", "<html></html>".getBytes());

        ResponseEntity<?> res = controller.uploadContentHtmlFile("p1", "w1", file, req);

        String url = (String) ((Map<?, ?>) res.getBody()).get("url");
        assertThat(url).startsWith("/api/assets/content-widgets/w1/").endsWith("_landing.html");
    }

    @Test
    void uploadContentHtmlFile_rejectsNonHtml() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "notes.txt", "text/plain", "x".getBytes());

        assertThat(controller.uploadContentHtmlFile("p1", "w1", file, req).getStatusCode().value()).isEqualTo(400);
        verify(blobStorageService, never()).upload(anyString(), any(byte[].class));
    }

    @Test
    void uploadContentImage_missingWidgetIs404() throws Exception {
        when(pageWidgetService.get("missing")).thenThrow(new NoSuchElementException("PageWidget not found: missing"));
        MockMultipartFile file = new MockMultipartFile("image", "logo.png", "image/png", "bytes".getBytes());

        assertThat(controller.uploadContentImage("p1", "missing", file, req).getStatusCode().value()).isEqualTo(404);
        verify(blobStorageService, never()).upload(anyString(), any(byte[].class));
    }
}
