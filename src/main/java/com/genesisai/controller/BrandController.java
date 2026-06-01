package com.genesisai.controller;

import com.genesisai.model.Brand;
import com.genesisai.repository.BrandRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/brands")
@RequiredArgsConstructor
@Tag(name = "Brands", description = "Manage brand contexts for Genesis AI conversations")
public class BrandController {

    private final BrandRepository brandRepo;

    @GetMapping
    @Operation(summary = "List all brands")
    public List<Brand> list() {
        return brandRepo.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a brand by ID")
    public Brand get(@PathVariable String id) {
        return brandRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Brand not found: " + id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a brand context")
    public Brand create(@RequestBody CreateBrandRequest req) {
        Brand brand = new Brand();
        brand.setId(UUID.randomUUID().toString());
        brand.setName(req.name());
        brand.setBrief(req.brief());
        brand.setIndustry(req.industry());
        brand.setAudience(req.audience());
        return brandRepo.save(brand);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a brand context")
    public Brand update(@PathVariable String id, @RequestBody CreateBrandRequest req) {
        Brand brand = brandRepo.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Brand not found: " + id));
        brand.setName(req.name());
        brand.setBrief(req.brief());
        brand.setIndustry(req.industry());
        brand.setAudience(req.audience());
        brand.setUpdatedAt(java.time.Instant.now());
        return brandRepo.save(brand);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a brand")
    public void delete(@PathVariable String id) {
        brandRepo.deleteById(id);
    }

    public record CreateBrandRequest(
        String name,
        String industry,
        String audience,
        String brief
    ) {}
}
