package ai.genesisbrands.service;

import ai.genesisbrands.model.Page;
import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.platform.PageLayout;
import ai.genesisbrands.platform.WidgetConfigOption;
import ai.genesisbrands.platform.WidgetDescriptor;
import ai.genesisbrands.repository.PageRepository;
import ai.genesisbrands.repository.PageWidgetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PageWidgetService {

    private final PageWidgetRepository pageWidgetRepo;
    private final PageRepository pageRepo;
    private final List<WidgetDescriptor> widgetDescriptors;
    private final ObjectMapper objectMapper;

    public List<PageWidget> listByPage(String pageId) {
        return pageWidgetRepo.findByPageIdOrderByOrderInSlotAsc(pageId);
    }

    public PageWidget get(String id) {
        return pageWidgetRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("PageWidget not found: " + id));
    }

    public PageWidget create(String pageId, String slotKey, String widgetType, String label, String configJson) {
        Page page = pageRepo.findById(pageId)
            .orElseThrow(() -> new IllegalArgumentException("Page not found: " + pageId));
        validateSlot(page, slotKey);
        WidgetDescriptor descriptor = validateWidgetType(widgetType);
        validateConfigJson(descriptor, configJson);

        int nextOrder = pageWidgetRepo.findByPageIdOrderByOrderInSlotAsc(pageId).stream()
            .filter(w -> w.getSlotKey().equals(slotKey))
            .mapToInt(PageWidget::getOrderInSlot)
            .max()
            .orElse(-1) + 1;

        PageWidget widget = new PageWidget();
        widget.setId(UUID.randomUUID().toString());
        widget.setPageId(pageId);
        widget.setSlotKey(slotKey);
        widget.setOrderInSlot(nextOrder);
        widget.setWidgetType(widgetType);
        widget.setLabel(label);
        widget.setConfigJson(configJson);
        return pageWidgetRepo.save(widget);
    }

    public PageWidget update(String id, String slotKey, int orderInSlot, String label, String configJson) {
        PageWidget widget = get(id);
        Page page = pageRepo.findById(widget.getPageId())
            .orElseThrow(() -> new NoSuchElementException("Page not found: " + widget.getPageId()));
        validateSlot(page, slotKey);
        WidgetDescriptor descriptor = validateWidgetType(widget.getWidgetType());
        validateConfigJson(descriptor, configJson);

        widget.setSlotKey(slotKey);
        widget.setOrderInSlot(orderInSlot);
        widget.setLabel(label);
        widget.setConfigJson(configJson);
        widget.setUpdatedAt(Instant.now());
        return pageWidgetRepo.save(widget);
    }

    public void delete(String id) {
        get(id); // validate exists
        pageWidgetRepo.deleteById(id);
    }

    private void validateSlot(Page page, String slotKey) {
        PageLayout layout = PageLayout.byId(page.getLayoutKey());
        if (layout == null) {
            throw new IllegalArgumentException("Page has an unknown layout: " + page.getLayoutKey());
        }
        boolean validSlot = layout.slots().stream().anyMatch(s -> s.key().equals(slotKey));
        if (!validSlot) {
            throw new IllegalArgumentException(
                "Layout '" + layout.id() + "' has no slot '" + slotKey + "'");
        }
    }

    private WidgetDescriptor validateWidgetType(String widgetType) {
        return widgetDescriptors.stream()
            .filter(d -> d.widgetType().equals(widgetType))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown widget type: " + widgetType));
    }

    private void validateConfigJson(WidgetDescriptor descriptor, String configJson) {
        if (configJson == null || configJson.isBlank()) return;
        Set<String> allowedKeys = descriptor.configOptions().stream()
            .map(WidgetConfigOption::key)
            .collect(Collectors.toSet());
        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(configJson, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("configJson must be a valid JSON object");
        }
        for (String key : parsed.keySet()) {
            if (!allowedKeys.contains(key)) {
                throw new IllegalArgumentException(
                    "Unknown config key '" + key + "' for widget type '" + descriptor.widgetType() + "'");
            }
        }
    }
}
