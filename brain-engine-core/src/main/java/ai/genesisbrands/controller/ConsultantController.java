package ai.genesisbrands.controller;

import ai.genesisbrands.model.ConversationMessage;
import ai.genesisbrands.service.ConsultantService;
import ai.genesisbrands.service.ConsultantSubjectProvider;
import ai.genesisbrands.service.ConsultantSubjectSummary;
import ai.genesisbrands.service.ContextEnrichmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Generic REST facade over {@link ConsultantService} — the platform's Consultant chat
 * feature, scoped to whatever {@link ConsultantSubjectProvider} the tenant registers.
 * Instantiated only via the {@code consultantController} @Bean in
 * {@link ai.genesisbrands.config.ConsultantServiceConfiguration}, never by component
 * scan — both {@code GenesisAiApplication} and {@code GenesisOsApplication} explicitly
 * exclude this class from scanning. Two things depend on that split: (1) it must be
 * {@code @RestController} (not a bare {@code @RequestMapping}) for
 * {@code RequestMappingHandlerMapping} to register its methods as handlers — confirmed
 * empirically that a class-level {@code @RequestMapping} alone isn't detected as a
 * handler in this Spring Boot version; (2) because {@code @RestController} is itself
 * {@code @Component}-meta-annotated, leaving it scannable would let a bare platform
 * deployment (no {@code ConsultantSubjectProvider} bean) register this bean
 * unconditionally via scan and fail to autowire its constructor — confirmed
 * reproducible. Routing the {@code @ConditionalOnBean} through the explicit @Bean method
 * instead of a class-level annotation also sidesteps scan-order sensitivity: the
 * packaged-jar build (different classpath enumeration order than local Gradle bootRun)
 * was observed to fail this condition nondeterministically when it lived on the class.
 */
@RestController
@RequestMapping("/api/consultant")
@RequiredArgsConstructor
@Tag(name = "Consultant", description = "Conversational interface with Genesis AI")
public class ConsultantController {

    private final ConsultantService consultant;
    private final ConsultantSubjectProvider subjectProvider;
    private final ContextEnrichmentService enrichment;

    @GetMapping("/subjects")
    @Operation(summary = "List all consultant subjects (e.g. brands)")
    public List<ConsultantSubjectSummary> list(
        @RequestParam(value = "origin", required = false, defaultValue = "consultant") String origin
    ) {
        return consultant.listSubjects(sourceOf(origin));
    }

    @PostMapping("/subjects")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new consultant subject")
    public ConsultantSubjectSummary create(@RequestBody CreateSubjectRequest req) {
        return subjectProvider.create(req.name(), req.industry(), req.audience(), req.brief());
    }

    @DeleteMapping("/subjects/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a consultant subject")
    public void delete(@PathVariable String id) {
        subjectProvider.delete(id);
    }

    @PostMapping(
        value = "/subjects/{id}/chat",
        consumes = {MediaType.MULTIPART_FORM_DATA_VALUE, MediaType.APPLICATION_JSON_VALUE},
        produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    @Operation(summary = "Send a message to Genesis AI — returns a streaming SSE response. " +
                         "Optionally attach a PDF or text file for additional context. " +
                         "URLs in the message are fetched automatically.")
    public Flux<String> chat(
        @PathVariable String id,
        @RequestPart("message") String message,
        @RequestPart(value = "file", required = false) MultipartFile file,
        @RequestParam(value = "origin", required = false, defaultValue = "consultant") String origin
    ) {
        String attachmentText = enrichment.extractFromFile(file);
        List<ContextEnrichmentService.UrlContent> urlContents = enrichment.fetchUrls(message);
        return consultant.chat(id, message, attachmentText, urlContents, sourceOf(origin));
    }

    @GetMapping("/subjects/{id}/chat/history")
    @Operation(summary = "Get conversation history for a subject")
    public List<ConversationMessage> history(
        @PathVariable String id,
        @RequestParam(value = "origin", required = false, defaultValue = "consultant") String origin
    ) {
        return consultant.history(id, sourceOf(origin));
    }

    @DeleteMapping("/subjects/{id}/chat/history")
    @Operation(summary = "Clear conversation history for a subject")
    public void clearHistory(
        @PathVariable String id,
        @RequestParam(value = "origin", required = false, defaultValue = "consultant") String origin
    ) {
        consultant.clearHistory(id, sourceOf(origin));
    }

    // "origin" is the wire-level name (matches the ?origin= query param callers pass);
    // Source is the internal enum. Anything but an exact "playground" match falls back to
    // CONSULTANT, which is also what absorbs legacy rows saved before this column existed.
    private ConversationMessage.Source sourceOf(String origin) {
        return "playground".equalsIgnoreCase(origin)
            ? ConversationMessage.Source.PLAYGROUND
            : ConversationMessage.Source.CONSULTANT;
    }

    public record CreateSubjectRequest(String name, String industry, String audience, String brief) {}
}
