package ai.genesisbrands.controller;

import ai.genesisbrands.agent.brandbook.BaselineBrandBookService;
import ai.genesisbrands.agent.brandbook.BaselineOutput;
import ai.genesisbrands.agent.brandbook.BrandBookAgent;
import ai.genesisbrands.agent.brandbook.BrandBookInput;
import ai.genesisbrands.agent.brandbook.BrandBookOutput;
import ai.genesisbrands.agent.brandbook.BrandBookTemplateRenderer;
import ai.genesisbrands.agent.brandbook.BrandBookRefinementLoop;
import ai.genesisbrands.agent.core.AgentRevision;
import ai.genesisbrands.service.BlobStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

@RestController
@RequestMapping("/api/agents/brand-book")
public class BrandBookAgentController {

    private static final Logger log = LoggerFactory.getLogger(BrandBookAgentController.class);

    private final BrandBookAgent brandBookAgent;
    private final BaselineBrandBookService baselineBrandBookService;
    private final BrandBookRefinementLoop brandBookRefinementLoop;
    private final BrandBookTemplateRenderer brandBookTemplateRenderer;
    private final BlobStorageService blobStorageService;

    public BrandBookAgentController(BrandBookAgent brandBookAgent,
                                     BaselineBrandBookService baselineBrandBookService,
                                     BrandBookRefinementLoop brandBookRefinementLoop,
                                     BrandBookTemplateRenderer brandBookTemplateRenderer,
                                     BlobStorageService blobStorageService) {
        this.brandBookAgent = brandBookAgent;
        this.baselineBrandBookService = baselineBrandBookService;
        this.brandBookRefinementLoop = brandBookRefinementLoop;
        this.brandBookTemplateRenderer = brandBookTemplateRenderer;
        this.blobStorageService = blobStorageService;
    }

    @PostMapping("/execute")
    public ResponseEntity<BrandBookOutput> execute(@RequestBody BrandBookInput input) {
        return ResponseEntity.ok(brandBookAgent.execute(input));
    }

    @PostMapping("/revise")
    public ResponseEntity<BrandBookOutput> revise(@RequestBody ReviseRequest request) {
        return ResponseEntity.ok(
            brandBookAgent.executeWithRevision(request.input(), request.previous(), request.revision())
        );
    }

    @PostMapping("/compare")
    public ResponseEntity<CompareResult> compare(@RequestBody BrandBookInput input) {
        long t0 = System.currentTimeMillis();
        BrandBookOutput genesisAi = brandBookAgent.execute(input);
        long t1 = System.currentTimeMillis();
        BaselineOutput baselineAi = baselineBrandBookService.generate(input);
        long t2 = System.currentTimeMillis();

        return ResponseEntity.ok(new CompareResult(genesisAi, baselineAi, t1 - t0, t2 - t1));
    }

    @PostMapping("/execute-with-evaluation")
    public ResponseEntity<BrandBookRefinementLoop.RefinementResult> executeWithEvaluation(@RequestBody BrandBookInput input) {
        return ResponseEntity.ok(brandBookRefinementLoop.run(input));
    }

    @PostMapping("/compare-with-evaluation")
    public ResponseEntity<CompareEvaluatedResult> compareWithEvaluation(@RequestBody BrandBookInput input) {
        long t0 = System.currentTimeMillis();
        BrandBookRefinementLoop.RefinementResult genesisAi = brandBookRefinementLoop.run(input);
        long t1 = System.currentTimeMillis();
        BaselineOutput baselineAi = baselineBrandBookService.generate(input);
        long t2 = System.currentTimeMillis();

        return ResponseEntity.ok(new CompareEvaluatedResult(genesisAi, baselineAi, t1 - t0, t2 - t1));
    }

    @PostMapping(value = "/render-pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> renderPdf(@RequestBody RenderPdfRequest request) {
        byte[] pdf = brandBookTemplateRenderer.render(request.input(), request.output());

        String blobPath = "assets/brand-books/%s/%s-%d.pdf".formatted(
            request.input().brief().engagementId(),
            request.input().brief().direction().name().toLowerCase(),
            request.output().iteration());
        try {
            blobStorageService.upload(blobPath, pdf);
        } catch (IOException e) {
            log.warn("Brand book PDF blob upload failed (non-fatal): {}", e.getMessage());
        }

        String filename = request.input().brief().brand().name().replaceAll("[^a-zA-Z0-9]+", "-") + "-brand-book.pdf";
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .body(pdf);
    }

    public record ReviseRequest(
        BrandBookInput input,
        BrandBookOutput previous,
        AgentRevision revision
    ) {}

    public record RenderPdfRequest(
        BrandBookInput input,
        BrandBookOutput output
    ) {}

    public record CompareResult(
        BrandBookOutput genesisAi,
        BaselineOutput baselineAi,
        long genesisAiMs,
        long baselineAiMs
    ) {}

    public record CompareEvaluatedResult(
        BrandBookRefinementLoop.RefinementResult genesisAi,
        BaselineOutput baselineAi,
        long genesisAiMs,
        long baselineAiMs
    ) {}
}
