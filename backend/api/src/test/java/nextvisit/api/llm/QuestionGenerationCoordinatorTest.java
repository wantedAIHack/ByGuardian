package nextvisit.api.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import nextvisit.api.common.Json;
import nextvisit.api.questions.QuestionCache;
import nextvisit.api.questions.QuestionCacheBody;
import nextvisit.api.questions.QuestionCacheRepository;
import nextvisit.api.questions.QuestionCacheStatus;
import nextvisit.api.snapshots.Snapshot;
import nextvisit.api.snapshots.SnapshotBody;
import nextvisit.api.snapshots.SnapshotKind;
import nextvisit.api.snapshots.SnapshotRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class QuestionGenerationCoordinatorTest {

    @Mock QuestionCacheRepository caches;
    @Mock SnapshotRepository snapshots;
    @Mock LlmClient client;
    @Mock QuestionGenerationResultWriter writer;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Json json = new Json(mapper);
    private final UUID caseId = UUID.randomUUID();
    private final UUID generationId = UUID.randomUUID();
    private QuestionCacheBody templates;
    private QuestionGenerationCoordinator coordinator;

    private static final String NOTE_TEXT = "합성: 오후마다 어깨를 만지심";

    @BeforeEach
    void setUp() {
        templates = new QuestionCacheBody(List.of(
            QuestionCacheBody.Q.template(1, "STALL", List.of("ambulation"), null, "걷기는 6주째 그대로입니다. 어떻게 보시나요?"),
            QuestionCacheBody.Q.template(2, "RISE_WITH_PAIN", List.of("toilet"),
                new QuestionCacheBody.SignalRef("STANDING", "GRIMACE"), "화장실 문장입니다. 괜찮을까요?")), 2);
        QuestionCache cache = new QuestionCache(caseId, 6, QuestionCacheStatus.LLM_PENDING,
            generationId, json.toJson(templates), Instant.parse("2026-09-07T00:00:00Z"));
        when(caches.findByCaseId(caseId)).thenReturn(Optional.of(cache));
        when(caches.existsByCaseIdAndGenerationIdAndStatus(caseId, generationId, QuestionCacheStatus.LLM_PENDING))
            .thenReturn(true);
        when(snapshots.findByCaseIdOrderByWeekAsc(caseId)).thenReturn(List.of(
            snapshot(3, new SnapshotBody(Map.of(), Map.of(), null,
                new SnapshotBody.FreeNote(NOTE_TEXT, "AFTERNOON")))));
        when(writer.markDone(any(), any(), any())).thenReturn(true);
        when(writer.markFailed(any(), any())).thenReturn(true);
        LlmProperties properties = new LlmProperties(true, URI.create("http://localhost:11434/v1"),
            "qwen3:4b-q4_K_M", "ollama", "", "", Duration.ofSeconds(3), Duration.ofSeconds(45), 3, 3000, "none", 4000);
        coordinator = new QuestionGenerationCoordinator(caches, snapshots, json, client,
            new SynthesisInputAssembler(properties), new QuestionSynthesisPrompt(mapper),
            new SynthesisValidator(mapper), writer, properties);
    }

    /**
     * 진짜 Snapshot을 만든다. mock(Snapshot.class)는 when(...) 안에서 다시 스텁하게 돼
     * UnfinishedStubbingException이 난다 — 엔티티 생성자가 값만 받으니 실물이 더 간단하다.
     */
    private Snapshot snapshot(int week, SnapshotBody body) {
        return new Snapshot(caseId, week, SnapshotKind.WEEKLY, false, UUID.randomUUID(),
            json.toJson(body), Instant.EPOCH);
    }

    private static final String GROUNDED = "{\"questions\":["
        + "{\"sentence\":\"오후마다 어깨를 자꾸 만지시는데 어떤 점을 살펴보면 좋을까요?\",\"detections\":[\"D2\"],\"noteWeeks\":[3]}]}";

    @Test
    void writesSynthesizedQuestionsWithOriginAndBasis() {
        when(client.complete(any())).thenReturn(GROUNDED);

        coordinator.generate(new QuestionGenerationRequested(caseId, generationId));

        ArgumentCaptor<QuestionCacheBody> body = ArgumentCaptor.forClass(QuestionCacheBody.class);
        verify(writer).markDone(eq(caseId), eq(generationId), body.capture());
        QuestionCacheBody.Q q = body.getValue().questions().get(0);
        assertThat(q.rank()).isEqualTo(1);
        assertThat(q.type()).isEqualTo(QuestionCacheBody.TYPE_SYNTHESIS);
        assertThat(q.sentence()).isEqualTo("오후마다 어깨를 자꾸 만지시는데 어떤 점을 살펴보면 좋을까요?");
        assertThat(q.templateSentence()).isEqualTo(q.sentence());
        assertThat(q.source()).isEqualTo(QuestionCacheBody.SOURCE_LLM);
        assertThat(q.origin()).isEqualTo(QuestionCacheBody.ORIGIN_LLM);
        assertThat(q.items()).containsExactly("toilet");
        assertThat(q.signal()).isEqualTo(new QuestionCacheBody.SignalRef("STANDING", "GRIMACE"));
        assertThat(q.basis()).isEqualTo(new QuestionCacheBody.Basis(List.of("D2"), List.of(3)));
        assertThat(body.getValue().engineDetectionCount()).isEqualTo(2);
    }

    @Test
    void retriesWithTheRuleNameAndFailsWithTheLastRule(CapturedOutput output) {
        when(client.complete(any())).thenReturn("{\"questions\":[]}");

        coordinator.generate(new QuestionGenerationRequested(caseId, generationId));

        ArgumentCaptor<LlmPrompt> prompts = ArgumentCaptor.forClass(LlmPrompt.class);
        verify(client, times(3)).complete(prompts.capture());
        assertThat(prompts.getAllValues().get(0).systemMessage()).doesNotContain("QUESTION_COUNT");
        assertThat(prompts.getAllValues().get(1).systemMessage()).contains("QUESTION_COUNT");
        verify(writer).markFailed(caseId, generationId);
        verify(writer, never()).markDone(any(), any(), any());
        assertThat(output).contains("attempts=3").contains("code=QUESTION_COUNT")
            .doesNotContain("오후마다");
    }

    @Test
    void failsWithoutCallingTheModelWhenNoNoteFits(CapturedOutput output) {
        when(snapshots.findByCaseIdOrderByWeekAsc(caseId)).thenReturn(List.of(
            snapshot(3, new SnapshotBody(Map.of(), Map.of(), null, null))));

        coordinator.generate(new QuestionGenerationRequested(caseId, generationId));

        verify(client, never()).complete(any());
        verify(writer).markFailed(caseId, generationId);
        assertThat(output).contains("code=NO_NOTES");
    }

    @Test
    void ignoresAStaleGeneration() {
        when(caches.findByCaseId(caseId)).thenReturn(Optional.of(new QuestionCache(caseId, 6,
            QuestionCacheStatus.LLM_PENDING, UUID.randomUUID(), json.toJson(templates), Instant.EPOCH)));

        coordinator.generate(new QuestionGenerationRequested(caseId, generationId));

        verify(client, never()).complete(any());
        verify(writer, never()).markFailed(any(), any());
    }

    @Test
    void makesNoProviderCallWhenTheCacheIsGone() {
        when(caches.findByCaseId(caseId)).thenReturn(Optional.empty());

        coordinator.generate(new QuestionGenerationRequested(caseId, generationId));

        verifyNoInteractions(client);
        verifyNoInteractions(writer);
    }

    @Test
    void mapsClientFailuresToTheirCode(CapturedOutput output) {
        when(client.complete(any())).thenThrow(new LlmClientException(LlmFailureCode.TIMEOUT));

        coordinator.generate(new QuestionGenerationRequested(caseId, generationId));

        verify(client, times(3)).complete(any());
        assertThat(output).contains("code=TIMEOUT");
    }

    @Test
    void generationThatBecomesStaleBetweenAttemptsStopsWithoutFinalWrite() {
        when(caches.existsByCaseIdAndGenerationIdAndStatus(
            caseId, generationId, QuestionCacheStatus.LLM_PENDING)).thenReturn(true, false);
        when(client.complete(any())).thenReturn("{\"questions\":[]}");

        coordinator.generate(new QuestionGenerationRequested(caseId, generationId));

        verify(client).complete(any());
        verifyNoInteractions(writer);
    }

    @Test
    void logsContainMetadataButNoCaseNoteOrQuestionContent(CapturedOutput output) {
        when(client.complete(any())).thenReturn("{\"questions\":[{\"sentence\":"
            + "\"SENSITIVESENTINEL 재활은 어떻게 보시나요?\",\"detections\":[\"D1\"],\"noteWeeks\":[3]}]}");

        coordinator.generate(new QuestionGenerationRequested(caseId, generationId));

        assertThat(output).contains("qwen3:4b-q4_K_M", generationId.toString(), "FORBIDDEN_WORD");
        assertThat(output).doesNotContain(caseId.toString(), "SENSITIVESENTINEL", NOTE_TEXT,
            templates.questions().get(0).templateSentence());
    }
}
