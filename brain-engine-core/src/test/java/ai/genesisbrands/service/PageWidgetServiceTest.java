package ai.genesisbrands.service;

import ai.genesisbrands.model.Page;
import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.platform.PageLayout;
import ai.genesisbrands.platform.WidgetDescriptor;
import ai.genesisbrands.repository.PageRepository;
import ai.genesisbrands.repository.PageWidgetRepository;
import ai.genesisbrands.widget.ContentWidget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PageWidgetServiceTest {

    @Mock private PageWidgetRepository pageWidgetRepo;
    @Mock private PageRepository pageRepo;
    @Mock private BlobStorageService blobStorageService;

    private List<WidgetDescriptor> widgetDescriptors;
    private PageWidgetService service;

    @BeforeEach
    void setUp() {
        widgetDescriptors = List.of(new ContentWidget());
        service = new PageWidgetService(pageWidgetRepo, pageRepo, widgetDescriptors, new ObjectMapper(), blobStorageService);
    }

    private Page page(String id, String layoutKey) {
        Page p = new Page();
        p.setId(id);
        p.setLayoutKey(layoutKey);
        return p;
    }

    private PageWidget widget(String id) {
        PageWidget w = new PageWidget();
        w.setId(id);
        w.setPageId("p1");
        w.setWidgetType("content");
        return w;
    }

    @Test
    void contentWidget_configOptionsIncludeAllMultiModeKeys() {
        List<String> keys = new ContentWidget().configOptions().stream()
            .map(o -> o.key())
            .toList();

        assertThat(keys).containsExactlyInAnyOrder(
            "contentMode", "heading", "body", "html", "imageUrl", "imageAlt",
            "htmlFileUrl", "widthMode", "width", "heightMode", "height");
    }

    @Test
    void create_rejectsUnknownConfigKey() {
        when(pageRepo.findById("p1")).thenReturn(Optional.of(page("p1", "SINGLE_COLUMN")));

        assertThatThrownBy(() -> service.create("p1", "main", "content", "", "{\"bogusKey\":\"x\"}"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_acceptsKnownContentModeKeys() {
        when(pageRepo.findById("p1")).thenReturn(Optional.of(page("p1", "FULL_BLEED")));
        when(pageWidgetRepo.findByPageIdOrderByOrderInSlotAsc("p1")).thenReturn(List.of());
        when(pageWidgetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PageWidget created = service.create("p1", "main", "content", "",
            "{\"contentMode\":\"htmlFile\",\"htmlFileUrl\":\"/api/assets/x.html\",\"widthMode\":\"percent\",\"width\":\"100\"}");

        assertThat(created.getWidgetType()).isEqualTo("content");
    }

    @Test
    void uploadContentImage_storesUnderContentWidgetsBlobPathAndReturnsAssetUrl() throws Exception {
        when(pageWidgetRepo.findById("w1")).thenReturn(Optional.of(widget("w1")));
        MockMultipartFile file = new MockMultipartFile("image", "logo.png", "image/png", "bytes".getBytes());

        String url = service.uploadContentImage("w1", file);

        assertThat(url).startsWith("/api/assets/content-widgets/w1/");
        assertThat(url).endsWith("_logo.png");
        verify(blobStorageService).upload(anyString(), any(byte[].class));
    }

    @Test
    void uploadContentHtmlFile_storesUnderContentWidgetsBlobPathAndReturnsAssetUrl() throws Exception {
        when(pageWidgetRepo.findById("w1")).thenReturn(Optional.of(widget("w1")));
        MockMultipartFile file = new MockMultipartFile("file", "landing.html", "text/html", "<html></html>".getBytes());

        String url = service.uploadContentHtmlFile("w1", file);

        assertThat(url).startsWith("/api/assets/content-widgets/w1/");
        assertThat(url).endsWith("_landing.html");
        verify(blobStorageService).upload(anyString(), any(byte[].class));
    }

    @Test
    void uploadContentImage_missingWidgetThrows() {
        when(pageWidgetRepo.findById("missing")).thenReturn(Optional.empty());
        MockMultipartFile file = new MockMultipartFile("image", "logo.png", "image/png", "bytes".getBytes());

        assertThatThrownBy(() -> service.uploadContentImage("missing", file))
            .isInstanceOf(java.util.NoSuchElementException.class);
    }
}
