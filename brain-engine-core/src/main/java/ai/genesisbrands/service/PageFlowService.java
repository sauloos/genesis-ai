package ai.genesisbrands.service;

import ai.genesisbrands.model.Page;
import ai.genesisbrands.model.PageFlow;
import ai.genesisbrands.repository.PageFlowRepository;
import ai.genesisbrands.repository.PageRepository;
import ai.genesisbrands.repository.PageWidgetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PageFlowService {

    private final PageFlowRepository pageFlowRepo;
    private final PageRepository pageRepo;
    private final PageWidgetRepository pageWidgetRepo;

    public List<PageFlow> list() {
        return pageFlowRepo.findAll();
    }

    public PageFlow get(String id) {
        return pageFlowRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("PageFlow not found: " + id));
    }

    public PageFlow create(String name, String slug) {
        PageFlow flow = new PageFlow();
        flow.setId(UUID.randomUUID().toString());
        flow.setName(name);
        flow.setSlug(slug);
        return pageFlowRepo.save(flow);
    }

    public PageFlow rename(String id, String name, String slug) {
        PageFlow flow = get(id);
        flow.setName(name);
        flow.setSlug(slug);
        flow.setUpdatedAt(Instant.now());
        return pageFlowRepo.save(flow);
    }

    @Transactional
    public PageFlow setLive(String id) {
        PageFlow flow = get(id);
        pageFlowRepo.deactivateAllForSlug(flow.getSlug());
        flow.setLive(true);
        flow.setUpdatedAt(Instant.now());
        return pageFlowRepo.save(flow);
    }

    public PageFlow setStartPage(String id, String startPageId) {
        PageFlow flow = get(id);
        if (startPageId != null) {
            Page target = pageRepo.findById(startPageId)
                .orElseThrow(() -> new IllegalArgumentException("Page not found: " + startPageId));
            if (!target.getPageFlowId().equals(id)) {
                throw new IllegalArgumentException("Start page must belong to this PageFlow: " + startPageId);
            }
        }
        flow.setStartPageId(startPageId);
        flow.setUpdatedAt(Instant.now());
        return pageFlowRepo.save(flow);
    }

    public PageFlow setEndConfig(String id, String endAction, String endPageId, String endTargetFlowId) {
        PageFlow flow = get(id);
        if (endAction != null) {
            if (!Set.of("END_PAGE", "REDIRECT_FLOW").contains(endAction)) {
                throw new IllegalArgumentException("Unknown endAction: " + endAction);
            }
            if (endAction.equals("END_PAGE")) {
                if (endPageId == null) {
                    throw new IllegalArgumentException("endPageId is required when endAction is END_PAGE");
                }
                Page page = pageRepo.findById(endPageId)
                    .orElseThrow(() -> new IllegalArgumentException("Page not found: " + endPageId));
                if (!page.getPageFlowId().equals(id)) {
                    throw new IllegalArgumentException("End page must belong to this PageFlow: " + endPageId);
                }
                if (!page.isEndPage()) {
                    throw new IllegalArgumentException("Page is not flagged as an End page: " + endPageId);
                }
            } else {
                if (endTargetFlowId == null) {
                    throw new IllegalArgumentException("endTargetFlowId is required when endAction is REDIRECT_FLOW");
                }
                pageFlowRepo.findById(endTargetFlowId)
                    .orElseThrow(() -> new IllegalArgumentException("Target PageFlow not found: " + endTargetFlowId));
            }
        }
        flow.setEndAction(endAction);
        flow.setEndPageId(endAction != null && endAction.equals("END_PAGE") ? endPageId : null);
        flow.setEndTargetFlowId(endAction != null && endAction.equals("REDIRECT_FLOW") ? endTargetFlowId : null);
        flow.setUpdatedAt(Instant.now());
        return pageFlowRepo.save(flow);
    }

    /** Populates each page's effectivePreviousPageId/effectiveErrorPageId (explicit value, else the computed default). */
    public List<Page> withEffectiveNav(List<Page> pages) {
        Page errorPage = pages.stream()
            .filter(Page::isErrorPage)
            .min(Comparator.comparing(Page::getCreatedAt))
            .orElse(null);
        for (Page page : pages) {
            if (page.getPreviousPageId() != null) {
                page.setEffectivePreviousPageId(page.getPreviousPageId());
            } else {
                pages.stream()
                    .filter(p -> page.getId().equals(p.getNextPageId()))
                    .max(Comparator.comparing(Page::getUpdatedAt))
                    .ifPresent(p -> page.setEffectivePreviousPageId(p.getId()));
            }
            if (page.getErrorPageId() != null) {
                page.setEffectiveErrorPageId(page.getErrorPageId());
            } else if (errorPage != null && !errorPage.getId().equals(page.getId())) {
                page.setEffectiveErrorPageId(errorPage.getId());
            }
        }
        return pages;
    }

    @Transactional
    public void delete(String id) {
        get(id); // validate exists
        for (Page page : pageRepo.findByPageFlowId(id)) {
            pageWidgetRepo.deleteByPageId(page.getId());
        }
        pageRepo.deleteByPageFlowId(id);
        pageFlowRepo.deleteById(id);
    }
}
