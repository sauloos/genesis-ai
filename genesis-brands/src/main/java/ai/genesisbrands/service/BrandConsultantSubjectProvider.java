package ai.genesisbrands.service;

import ai.genesisbrands.model.Brand;
import ai.genesisbrands.repository.BrandRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Genesis Brands' {@link ConsultantSubjectProvider}: a consultant conversation is scoped
 * to a {@link Brand}. Its presence as a bean is what turns on {@code ConsultantService}
 * (see {@code ConsultantServiceConfiguration} in brain-engine-core).
 */
@Component
public class BrandConsultantSubjectProvider implements ConsultantSubjectProvider {

    private final BrandRepository brandRepo;

    public BrandConsultantSubjectProvider(BrandRepository brandRepo) {
        this.brandRepo = brandRepo;
    }

    @Override
    public ConsultantSubject find(String subjectId) {
        Brand brand = brandRepo.findById(subjectId)
            .orElseThrow(() -> new IllegalArgumentException("Brand not found: " + subjectId));
        return new ConsultantSubject(brand.getId(), brand.getName(), brand.getIndustry(), brand.getAudience(), brand.getBrief());
    }

    @Override
    public List<ConsultantSubjectSummary> list() {
        return brandRepo.findAll().stream()
            .map(b -> new ConsultantSubjectSummary(b.getId(), b.getName(), b.getUpdatedAt()))
            .toList();
    }

    @Override
    public ConsultantSubjectSummary create(String name, String industry, String audience, String brief) {
        Brand brand = new Brand();
        brand.setId(UUID.randomUUID().toString());
        brand.setName(name);
        brand.setIndustry(industry);
        brand.setAudience(audience);
        brand.setBrief(brief);
        brand = brandRepo.save(brand);
        return new ConsultantSubjectSummary(brand.getId(), brand.getName(), brand.getUpdatedAt());
    }

    @Override
    public void delete(String subjectId) {
        brandRepo.deleteById(subjectId);
    }
}
