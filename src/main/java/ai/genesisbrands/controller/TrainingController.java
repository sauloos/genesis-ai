package ai.genesisbrands.controller;

import ai.genesisbrands.model.TrainingContent;
import ai.genesisbrands.model.TrainingSession;
import ai.genesisbrands.service.TrainingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/training")
@RequiredArgsConstructor
@Tag(name = "Training", description = "Knowledge authoring — build operative guidelines and knowledge for specialist agents")
public class TrainingController {

    private final TrainingService trainingService;

    // ── Sessions ──────────────────────────────────────────────────────────────

    @GetMapping("/sessions")
    @Operation(summary = "List all training sessions")
    public List<TrainingSession> listSessions() {
        return trainingService.listSessions();
    }

    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new training session")
    public TrainingSession createSession(@RequestBody CreateSessionRequest req) {
        return trainingService.createSession(req.topic());
    }

    @GetMapping("/sessions/{id}")
    @Operation(summary = "Get a training session with its content")
    public SessionDetail getSession(@PathVariable String id) {
        TrainingSession session = trainingService.getSession(id);
        List<TrainingContent> content = trainingService.listContent(id);
        return new SessionDetail(session, content);
    }

    @PutMapping("/sessions/{id}")
    @Operation(summary = "Update session classification (intent, scope, asset types, topic)")
    public TrainingSession updateSession(@PathVariable String id, @RequestBody UpdateSessionRequest req) {
        return trainingService.updateSession(id, req.topic(), req.intent(), req.scope(), req.assetTypes());
    }

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a session and all its content")
    public void deleteSession(@PathVariable String id) {
        trainingService.deleteSession(id);
    }

    // ── Content ───────────────────────────────────────────────────────────────

    @PostMapping("/sessions/{id}/content/text")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a text note to a session")
    public TrainingContent addText(@PathVariable String id, @RequestBody AddTextRequest req) {
        return trainingService.addText(id, req.text());
    }

    @PostMapping("/sessions/{id}/content/file")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload a file (PDF, txt, md) to a session")
    public TrainingContent addFile(
        @PathVariable String id,
        @RequestParam("file") MultipartFile file
    ) throws IOException {
        return trainingService.addFile(id, file);
    }

    @PostMapping("/sessions/{id}/content/audio")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Upload audio — transcribed automatically via Whisper")
    public TrainingContent addAudio(
        @PathVariable String id,
        @RequestParam("audio") MultipartFile audio
    ) throws IOException {
        return trainingService.addAudio(id, audio);
    }

    @DeleteMapping("/sessions/{id}/content/{contentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a content item from a session")
    public void deleteContent(@PathVariable String id, @PathVariable String contentId) {
        trainingService.deleteContent(id, contentId);
    }

    // ── Ingest ────────────────────────────────────────────────────────────────

    @PostMapping("/sessions/{id}/ingest")
    @Operation(summary = "Embed and ingest all session content into Qdrant")
    public TrainingSession ingest(@PathVariable String id) {
        trainingService.ingest(id);
        return trainingService.getSession(id);
    }

    // ── Feedback (playground-sourced) ────────────────────────────────────────

    @PostMapping("/feedback")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit written feedback on a playground run asset — enters the review queue")
    public TrainingSession submitFeedback(@RequestBody SubmitFeedbackRequest req) throws IOException {
        return trainingService.submitFeedback(
            req.playgroundSessionId(), req.assetType(), req.label(),
            req.feedbackText(), null, req.agentReasoning(), req.assetUrls()
        );
    }

    @PostMapping(value = "/feedback/audio", consumes = "multipart/form-data")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Submit voice feedback on a playground run asset — transcribed, enters the review queue")
    public TrainingSession submitFeedbackAudio(
        @RequestParam String playgroundSessionId,
        @RequestParam String assetType,
        @RequestParam TrainingContent.Label label,
        @RequestParam String agentReasoning,
        @RequestParam List<String> assetUrls,
        @RequestParam("audio") MultipartFile audio
    ) throws IOException {
        return trainingService.submitFeedback(
            playgroundSessionId, assetType, label, null, audio, agentReasoning, assetUrls
        );
    }

    @PostMapping("/sessions/{id}/approve")
    @Operation(summary = "Approve a pending-review feedback session and ingest it into Qdrant")
    public TrainingSession approve(@PathVariable String id) {
        trainingService.approveSession(id);
        return trainingService.getSession(id);
    }

    @PostMapping("/sessions/{id}/reject")
    @Operation(summary = "Reject a pending-review feedback session — retained, never ingested")
    public TrainingSession reject(@PathVariable String id) {
        return trainingService.rejectSession(id);
    }

    // ── Records ───────────────────────────────────────────────────────────────

    public record CreateSessionRequest(String topic) {}
    public record UpdateSessionRequest(
        String topic,
        TrainingSession.Intent intent,
        TrainingSession.Scope scope,
        String assetTypes
    ) {}
    public record AddTextRequest(String text) {}
    public record SessionDetail(TrainingSession session, List<TrainingContent> content) {}
    public record SubmitFeedbackRequest(
        String playgroundSessionId,
        String assetType,
        TrainingContent.Label label,
        String feedbackText,
        String agentReasoning,
        List<String> assetUrls
    ) {}
}
