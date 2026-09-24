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
    private final PageTransitionService pageTransitionService;

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

    public Page updateMetadata(String id, String name, boolean requiresAuth, boolean errorPage, boolean endPage, boolean bordered) {
        Page page = get(id);
        page.setName(name);
        page.setRequiresAuth(requiresAuth);
        page.setErrorPage(errorPage);
        page.setEndPage(endPage);
        page.setBordered(bordered);
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

    public Page updatePreviousPage(String id, String previousPageId) {
        Page page = get(id);
        if (previousPageId != null) {
            Page target = pageRepo.findById(previousPageId)
                .orElseThrow(() -> new IllegalArgumentException("Target page not found: " + previousPageId));
            if (!target.getPageFlowId().equals(page.getPageFlowId())) {
                throw new IllegalArgumentException("Target page must belong to the same PageFlow: " + previousPageId);
            }
        }
        page.setPreviousPageId(previousPageId);
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
        pageTransitionService.deleteForPage(id);
        pageRepo.deleteById(id);
    }
}
