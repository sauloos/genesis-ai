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
import java.util.List;
import java.util.NoSuchElementException;
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
