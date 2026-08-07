package ai.genesisbrands.controller;

import ai.genesisbrands.model.Questionnaire;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.model.QuestionnaireResponse;
import ai.genesisbrands.service.QuestionnaireResponseService;
import ai.genesisbrands.service.QuestionnaireService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/questionnaires")
@RequiredArgsConstructor
@Tag(name = "Questionnaires", description = "Configurable client discovery questionnaires — authoring, publishing, and fill-out")
public class QuestionnaireController {

    private final QuestionnaireService questionnaireService;
    private final QuestionnaireResponseService responseService;

    // ── Authoring: questionnaires ────────────────────────────────────────────

    @GetMapping
    @Operation(summary = "List all questionnaires")
    public List<Questionnaire> list() {
        return questionnaireService.listQuestionnaires();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new questionnaire")
    public Questionnaire create(@RequestBody CreateQuestionnaireRequest req) {
        return questionnaireService.createQuestionnaire(req.title(), req.description());
    }

    @GetMapping("/live")
    @Operation(summary = "Get the currently published questionnaire")
    public Questionnaire live() {
        return questionnaireService.getLive();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a questionnaire with its questions")
    public QuestionnaireDetail get(@PathVariable String id) {
        Questionnaire questionnaire = questionnaireService.getQuestionnaire(id);
        List<QuestionnaireQuestion> questions = questionnaireService.listQuestions(id);
        return new QuestionnaireDetail(questionnaire, questions);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a questionnaire's title/description")
    public Questionnaire update(@PathVariable String id, @RequestBody CreateQuestionnaireRequest req) {
        return questionnaireService.updateQuestionnaire(id, req.title(), req.description());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a questionnaire and all its questions/responses")
    public void delete(@PathVariable String id) {
        questionnaireService.deleteQuestionnaire(id);
    }

    // ── Authoring: questions ─────────────────────────────────────────────────

    @PostMapping("/{id}/questions")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a question to a questionnaire")
    public QuestionnaireQuestion addQuestion(@PathVariable String id, @RequestBody QuestionRequest req) {
        return questionnaireService.addQuestion(id, req.type(), req.prompt(), req.helpText(),
            req.required(), req.allowVoice(), req.configJson());
    }

    @PutMapping("/{id}/questions/{qid}")
    @Operation(summary = "Update a question")
    public QuestionnaireQuestion updateQuestion(@PathVariable String id, @PathVariable String qid,
                                                 @RequestBody QuestionRequest req) {
        return questionnaireService.updateQuestion(id, qid, req.type(), req.prompt(), req.helpText(),
            req.required(), req.allowVoice(), req.configJson());
    }

    @DeleteMapping("/{id}/questions/{qid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a question")
    public void deleteQuestion(@PathVariable String id, @PathVariable String qid) {
        questionnaireService.deleteQuestion(id, qid);
    }

    @PostMapping("/{id}/questions/reorder")
    @Operation(summary = "Reorder a questionnaire's questions")
    public List<QuestionnaireQuestion> reorderQuestions(@PathVariable String id, @RequestBody ReorderRequest req) {
        questionnaireService.reorderQuestions(id, req.orderedQuestionIds());
        return questionnaireService.listQuestions(id);
    }

    // ── Authoring: publishing ────────────────────────────────────────────────

    @PostMapping("/{id}/publish")
    @Operation(summary = "Publish a questionnaire now, or schedule it for a future publishAt")
    public Questionnaire publish(@PathVariable String id, @RequestBody PublishRequest req) {
        return questionnaireService.publish(id, req.publishAt());
    }

    @PostMapping("/{id}/unpublish")
    @Operation(summary = "Unpublish a questionnaire back to draft")
    public Questionnaire unpublish(@PathVariable String id) {
        return questionnaireService.unpublish(id);
    }

    // ── Runtime: responses ────────────────────────────────────────────────────

    @GetMapping("/{id}/responses")
    @Operation(summary = "List responses submitted to a questionnaire")
    public List<QuestionnaireResponse> listResponses(@PathVariable String id) {
        return responseService.listResponses(id);
    }

    @PostMapping("/{id}/responses")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a new fill-out attempt for a questionnaire")
    public QuestionnaireResponse startResponse(@PathVariable String id) {
        return responseService.startResponse(id);
    }

    @GetMapping("/responses/{responseId}/answers")
    @Operation(summary = "List answers recorded so far for a response")
    public List<QuestionnaireAnswer> listAnswers(@PathVariable String responseId) {
        return responseService.listAnswers(responseId);
    }

    @PutMapping("/responses/{responseId}/answers/{questionId}")
    @Operation(summary = "Save a text/slider/select answer")
    public QuestionnaireAnswer saveAnswer(@PathVariable String responseId, @PathVariable String questionId,
                                           @RequestBody SaveAnswerRequest req) {
        return responseService.saveAnswer(responseId, questionId, req.value());
    }

    @PostMapping(value = "/responses/{responseId}/answers/{questionId}/image", consumes = "multipart/form-data")
    @Operation(summary = "Upload an image answer")
    public QuestionnaireAnswer saveImageAnswer(@PathVariable String responseId, @PathVariable String questionId,
                                                @RequestParam("image") MultipartFile image) throws IOException {
        return responseService.saveImageAnswer(responseId, questionId, image);
    }

    @PostMapping(value = "/responses/{responseId}/answers/{questionId}/voice", consumes = "multipart/form-data")
    @Operation(summary = "Upload a voice answer — transcribed automatically via Whisper")
    public QuestionnaireAnswer saveVoiceAnswer(@PathVariable String responseId, @PathVariable String questionId,
                                                @RequestParam("audio") MultipartFile audio) throws IOException {
        return responseService.saveVoiceAnswer(responseId, questionId, audio);
    }

    @PostMapping("/responses/{responseId}/submit")
    @Operation(summary = "Submit a completed response")
    public QuestionnaireResponse submit(@PathVariable String responseId) {
        return responseService.submit(responseId);
    }

    // ── Records ───────────────────────────────────────────────────────────────

    public record CreateQuestionnaireRequest(String title, String description) {}
    public record QuestionnaireDetail(Questionnaire questionnaire, List<QuestionnaireQuestion> questions) {}
    public record QuestionRequest(QuestionnaireQuestion.Type type, String prompt, String helpText,
                                   boolean required, boolean allowVoice, String configJson) {}
    public record ReorderRequest(List<String> orderedQuestionIds) {}
    public record PublishRequest(Instant publishAt) {}
    public record SaveAnswerRequest(Object value) {}
}
