package ai.genesisbrands.service;

import ai.genesisbrands.model.Brand;
import ai.genesisbrands.repository.BrandRepository;
import org.springframework.stereotype.Component;

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
}
