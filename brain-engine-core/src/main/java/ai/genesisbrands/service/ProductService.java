package ai.genesisbrands.service;

import ai.genesisbrands.model.Product;
import ai.genesisbrands.model.ProductOption;
import ai.genesisbrands.repository.ProductOptionRepository;
import ai.genesisbrands.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepo;
    private final ProductOptionRepository optionRepo;

    // ── Products ──────────────────────────────────────────────────────────────

    public List<Product> listProducts(boolean all) {
        return all ? productRepo.findAllByOrderBySortOrderAsc() : productRepo.findByActiveTrueOrderBySortOrderAsc();
    }

    public Product getProduct(String id) {
        return productRepo.findById(id)
            .orElseThrow(() -> new NoSuchElementException("Product not found: " + id));
    }

    public Product createProduct(String name, String description, Product.Type type) {
        Product p = new Product();
        p.setId(UUID.randomUUID().toString());
        p.setName(name);
        p.setDescription(description);
        p.setType(type != null ? type : Product.Type.ONE_TIME);
        p.setSortOrder(productRepo.findAllByOrderBySortOrderAsc().size());
        return productRepo.save(p);
    }

    public Product updateProduct(String id, String name, String description, Product.Type type, boolean active) {
        Product p = getProduct(id);
        p.setName(name);
        p.setDescription(description);
        p.setType(type != null ? type : p.getType());
        p.setActive(active);
        p.setUpdatedAt(Instant.now());
        return productRepo.save(p);
    }

    @Transactional
    public void deleteProduct(String id) {
        optionRepo.deleteByProductId(id);
        productRepo.deleteById(id);
    }

    // ── Options ───────────────────────────────────────────────────────────────

    public List<ProductOption> listOptions(String productId) {
        return optionRepo.findByProductIdOrderBySortOrderAsc(productId);
    }

    public ProductOption addOption(String productId, String name, String description, long priceCents,
                                    String currency, String billingInterval, String featuresJson, boolean active) {
        getProduct(productId); // validate exists
        int nextIndex = optionRepo.findByProductIdOrderBySortOrderAsc(productId).size();

        ProductOption option = new ProductOption();
        option.setId(UUID.randomUUID().toString());
        option.setProductId(productId);
        option.setSortOrder(nextIndex);
        option.setName(name);
        option.setDescription(description);
        option.setPriceCents(priceCents);
        option.setCurrency(currency != null && !currency.isBlank() ? currency : "usd");
        option.setBillingInterval(billingInterval);
        option.setFeaturesJson(featuresJson);
        option.setActive(active);
        touchProduct(productId);
        return optionRepo.save(option);
    }

    public ProductOption updateOption(String productId, String optionId, String name, String description,
                                       long priceCents, String currency, String billingInterval,
                                       String featuresJson, boolean active) {
        ProductOption option = getOption(productId, optionId);
        option.setName(name);
        option.setDescription(description);
        option.setPriceCents(priceCents);
        option.setCurrency(currency != null && !currency.isBlank() ? currency : "usd");
        option.setBillingInterval(billingInterval);
        option.setFeaturesJson(featuresJson);
        option.setActive(active);
        option.setUpdatedAt(Instant.now());
        touchProduct(productId);
        return optionRepo.save(option);
    }

    @Transactional
    public void deleteOption(String productId, String optionId) {
        getOption(productId, optionId);
        optionRepo.deleteById(optionId);
        touchProduct(productId);
    }

    @Transactional
    public void reorderOptions(String productId, List<String> orderedOptionIds) {
        getProduct(productId);
        for (int i = 0; i < orderedOptionIds.size(); i++) {
            ProductOption option = getOption(productId, orderedOptionIds.get(i));
            option.setSortOrder(i);
            option.setUpdatedAt(Instant.now());
            optionRepo.save(option);
        }
        touchProduct(productId);
    }

    public ProductOption getOption(String productId, String optionId) {
        return optionRepo.findById(optionId)
            .filter(o -> o.getProductId().equals(productId))
            .orElseThrow(() -> new NoSuchElementException("Option not found: " + optionId));
    }

    /** Looks up an option without already knowing its parent product — used by the payment
     *  widget, which only has an optionId (persisted by ProductSelectionController) and
     *  needs to resolve the rest (price, product name) to render and charge correctly. */
    public java.util.Optional<ProductOption> findOptionById(String optionId) {
        return optionRepo.findById(optionId);
    }

    private void touchProduct(String id) {
        productRepo.findById(id).ifPresent(p -> {
            p.setUpdatedAt(Instant.now());
            productRepo.save(p);
        });
    }
}
