package ai.genesisbrands.service;

import ai.genesisbrands.model.Page;
import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.platform.PageLayout;
import ai.genesisbrands.platform.WidgetConfigOption;
import ai.genesisbrands.platform.WidgetConfigOption.OptionType;
import ai.genesisbrands.platform.WidgetDescriptor;
import ai.genesisbrands.repository.PageRepository;
import ai.genesisbrands.repository.PageWidgetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PageWidgetServiceTest {

    @Mock private PageWidgetRepository pageWidgetRepo;
    @Mock private PageRepository pageRepo;

    private List<WidgetDescriptor> widgetDescriptors;
    private PageWidgetService service;

    @BeforeEach
    void setUp() {
        widgetDescriptors = List.of(new StubContentWidget());
        service = new PageWidgetService(pageWidgetRepo, pageRepo, widgetDescriptors, new ObjectMapper());
    }

    /** Widget modules are not on the runtime's classpath; a stand-in "content" descriptor. */
    private static class StubContentWidget implements WidgetDescriptor {
        public String widgetType() { return "content"; }
        public String displayName() { return "Content"; }
        public String description() { return "Stub"; }
        public List<WidgetConfigOption> configOptions() {
            return List.of("contentMode", "htmlFileUrl", "widthMode", "width").stream()
                .map(k -> new WidgetConfigOption(k, k, OptionType.STRING, List.of(), ""))
                .toList();
        }
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
}
