package ai.genesisbrands.service;

import ai.genesisbrands.model.FlowSession;
import ai.genesisbrands.model.Page;
import ai.genesisbrands.model.PageFlow;
import ai.genesisbrands.model.PageWidget;
import ai.genesisbrands.model.QuestionnaireAnswer;
import ai.genesisbrands.model.QuestionnaireQuestion;
import ai.genesisbrands.platform.FlowAnswerSource;
import ai.genesisbrands.platform.FlowEngagementTrigger;
import ai.genesisbrands.repository.PageRepository;
import ai.genesisbrands.repository.PageWidgetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FlowEngagementServiceTest {

    @Mock private PageRepository pageRepo;
    @Mock private PageWidgetRepository pageWidgetRepo;
    @Mock private FlowEngagementTrigger flowEngagementTrigger;

    private FlowEngagementService service;

    @BeforeEach
    void setUp() {
        service = new FlowEngagementService(pageRepo, pageWidgetRepo, List.of(new EchoSource()),
            Optional.of(flowEngagementTrigger), new ObjectMapper());
    }

    /** Stand-in for a widget module's source: one question per widget, answered from the
     *  context key named in its config. */
    private static class EchoSource implements FlowAnswerSource {
        public String widgetType() { return "echo"; }
        public FlowAnswers collect(PageWidget widget, Map<String, Object> config, Map<String, Object> context) {
            Object key = config.get("key");
            if (!context.containsKey(key)) return FlowAnswers.NONE;
            QuestionnaireQuestion q = new QuestionnaireQuestion();
            q.setId(widget.getId());
            QuestionnaireAnswer a = new QuestionnaireAnswer();
            a.setQuestionId(widget.getId());
            a.setValueJson(String.valueOf(context.get(key)));
            return new FlowAnswers(List.of(q), List.of(a));
        }
    }

    private PageFlow flow(String id) {
        PageFlow f = new PageFlow();
        f.setId(id);
        return f;
    }

    private Page page(String id, String flowId) {
        Page p = new Page();
        p.setId(id);
        p.setPageFlowId(flowId);
        return p;
    }

    private PageWidget widget(String id, String pageId, String widgetType, String configJson) {
        PageWidget w = new PageWidget();
        w.setId(id);
        w.setPageId(pageId);
        w.setWidgetType(widgetType);
        w.setConfigJson(configJson);
        return w;
    }

    private FlowSession session(String flowId, String contextJson) {
        FlowSession s = new FlowSession();
        s.setToken("tok");
        s.setPageFlowId(flowId);
        s.setContextJson(contextJson);
        s.setExpiresAt(Instant.now().plusSeconds(3600));
        return s;
    }

    @Test
    void triggerEngagement_collectsFromWidgetsWithASource_inOrder_skippingOthers() {
        PageFlow f = flow("f1");
        when(pageRepo.findByPageFlowId("f1")).thenReturn(List.of(page("p1", "f1")));
        when(pageWidgetRepo.findByPageIdInOrderByOrderInSlotAsc(List.of("p1"))).thenReturn(List.of(
            widget("w1", "p1", "echo", "{\"key\":\"a\"}"),
            widget("w2", "p1", "content", "{\"heading\":\"Hi\"}"),
            widget("w3", "p1", "echo", "{\"key\":\"never\"}"),
            widget("w4", "p1", "echo", "{\"key\":\"b\"}")
        ));
        FlowSession s = session("f1", "{\"a\":\"Ada\",\"b\":\"Blue\"}");
        when(flowEngagementTrigger.createAndRun(anyList(), anyList(), isNull())).thenReturn("eng-1");

        service.triggerEngagement(f, s);

        ArgumentCaptor<List<QuestionnaireQuestion>> questionsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<QuestionnaireAnswer>> answersCaptor = ArgumentCaptor.forClass(List.class);
        verify(flowEngagementTrigger).createAndRun(questionsCaptor.capture(), answersCaptor.capture(), isNull());
        assertThat(questionsCaptor.getValue()).extracting(QuestionnaireQuestion::getId).containsExactly("w1", "w4");
        assertThat(answersCaptor.getValue()).extracting(QuestionnaireAnswer::getValueJson).containsExactly("Ada", "Blue");
    }

    @Test
    void triggerEngagement_returnsTheNewEngagementId() {
        PageFlow f = flow("f1");
        when(pageRepo.findByPageFlowId("f1")).thenReturn(List.of(page("p1", "f1")));
        when(pageWidgetRepo.findByPageIdInOrderByOrderInSlotAsc(List.of("p1"))).thenReturn(List.of());
        FlowSession s = session("f1", "{}");
        s.setClientUserId("u1");
        when(flowEngagementTrigger.createAndRun(anyList(), anyList(), eq("u1"))).thenReturn("eng-2");

        assertThat(service.triggerEngagement(f, s)).isEqualTo("eng-2");
    }

    @Test
    void nextUrl_asksTheTriggerForTheSessionsClient() {
        FlowSession s = session("f1", "{}");
        s.setClientUserId("u1");
        when(flowEngagementTrigger.nextUrl("eng-2", "u1")).thenReturn("/results/eng-2");

        assertThat(service.nextUrl("eng-2", s)).isEqualTo("/results/eng-2");
    }
}
