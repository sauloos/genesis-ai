package ai.genesisbrands.service;

import ai.genesisbrands.model.Page;
import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.platform.PageLayout;
import ai.genesisbrands.repository.PageRepository;
import ai.genesisbrands.repository.PageWidgetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class PageService {

    private final PageRepository pageRepo;
    private final PageWidgetRepository pageWidgetRepo;

    public List<Page> listByFlow(String pageFlowId) {
        return pageRepo.findByPageFlowId(pageFlowId);
    }

    public Page get(String id) {
        return pageRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Page not found: " + id));
    }

    public Page create(String pageFlowId, String name) {
        Page page = new Page();
        page.setId(UUID.randomUUID().toString());
        page.setPageFlowId(pageFlowId);
        page.setName(name);
        return pageRepo.save(page);
    }

    public Page updateMetadata(String id, String name, boolean requiresAuth, boolean errorPage) {
        Page page = get(id);
        page.setName(name);
        page.setRequiresAuth(requiresAuth);
        page.setErrorPage(errorPage);
        page.setUpdatedAt(Instant.now());
        return pageRepo.save(page);
    }

    public Page updatePosition(String id, double x, double y) {
        Page page = get(id);
        page.setCanvasX(x);
        page.setCanvasY(y);
        page.setUpdatedAt(Instant.now());
        return pageRepo.save(page);
    }

    public Page updateNavTargets(String id, String nextPageId, String previousPageId, String errorPageId) {
        Page page = get(id);
        for (String targetId : new String[] { nextPageId, previousPageId, errorPageId }) {
            if (targetId == null) continue;
            Page target = pageRepo.findById(targetId)
                .orElseThrow(() -> new IllegalArgumentException("Target page not found: " + targetId));
            if (!target.getPageFlowId().equals(page.getPageFlowId())) {
                throw new IllegalArgumentException("Target page must belong to the same PageFlow: " + targetId);
            }
        }
        page.setNextPageId(nextPageId);
        page.setPreviousPageId(previousPageId);
        page.setErrorPageId(errorPageId);
        page.setUpdatedAt(Instant.now());
        return pageRepo.save(page);
    }

    public Page updateLayout(String id, String layoutKey) {
        Page page = get(id);
        PageLayout layout = PageLayout.byId(layoutKey);
        if (layout == null) {
            throw new IllegalArgumentException("Unknown layout: " + layoutKey);
        }
        Set<String> validSlots = layout.slots().stream()
            .map(PageLayout.Slot::key)
            .collect(Collectors.toSet());
        List<PageWidget> widgets = pageWidgetRepo.findByPageIdOrderByOrderInSlotAsc(id);
        for (PageWidget widget : widgets) {
            if (!validSlots.contains(widget.getSlotKey())) {
                throw new IllegalArgumentException(
                    "Layout '" + layoutKey + "' has no slot '" + widget.getSlotKey()
                        + "' used by an existing widget placement");
            }
        }
        page.setLayoutKey(layoutKey);
        page.setUpdatedAt(Instant.now());
        return pageRepo.save(page);
    }

    @Transactional
    public void delete(String id) {
        get(id); // validate exists
        pageWidgetRepo.deleteByPageId(id);
        pageRepo.deleteById(id);
    }
}
