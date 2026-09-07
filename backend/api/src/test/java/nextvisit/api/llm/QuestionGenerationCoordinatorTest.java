package nextvisit.api.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import nextvisit.api.common.Json;
import nextvisit.api.questions.QuestionCache;
import nextvisit.api.questions.QuestionCacheBody;
import nextvisit.api.questions.QuestionCacheRepository;
import nextvisit.api.questions.QuestionCacheStatus;
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
    @Mock LlmClient client;
    @Mock QuestionGenerationResultWriter writer;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Json json = new Json(mapper);
    private final UUID caseId = UUID.randomUUID();
    private final UUID generationId = UUID.randomUUID();
    private QuestionCacheBody templates;
    private QuestionGenerationCoordinator coordinator;

    @BeforeEach
    void setUp() {
        templates = new QuestionCacheBody(List.of(
            question(1, "걷기를 3주 중 2주 지켜봤습니다. 어떻게 보시나요?"),
            question(2, "식사는 4주째 그대로입니다. 어떻게 보시나요?")), 2);
        QuestionCache cache = new QuestionCache(caseId, 6, QuestionCacheStatus.LLM_PENDING,
            generationId, json.toJson(templates), Instant.parse("2026-09-07T00:00:00Z"));
        when(caches.findByCaseId(caseId)).thenReturn(Optional.of(cache));
        when(caches.existsByCaseIdAndGenerationIdAndStatus(
            caseId, generationId, QuestionCacheStatus.LLM_PENDING)).thenReturn(true);
        when(writer.markDone(any(), any(), any())).thenReturn(true);
        when(writer.markFailed(any(), any())).thenReturn(true);
        LlmProperties properties = new LlmProperties(true,
            URI.create("http://localhost:11434/v1"), "qwen3:4b-q8_0", "ollama", "", "",
            Duration.ofSeconds(3), Duration.ofSeconds(45), 3, 512);
        coordinator = new QuestionGenerationCoordinator(caches, json, client,
            new QuestionRewritePrompt(mapper), new QuestionOutputGuard(mapper), writer, properties);
    }

    @Test
    void twoRejectedBatchesThenSuccessWritesEveryQuestionAsLlm() {
        String questionMarkRejected = "REJECTED_SENTINEL 3주 중 2주인가요??";
        String numberTokensRejected = "3주 중 1주인가요?";
        String questionMarkRejectedResponse = response(questionMarkRejected, "4주째인가요?");
        String numberTokensRejectedResponse = response(numberTokensRejected, "4주째인가요?");
        when(client.complete(any()))
            .thenReturn(questionMarkRejectedResponse)
            .thenReturn(numberTokensRejectedResponse)
            .thenReturn(response("걷기를 3주 중 2주 보셨는데 어떻게 보시나요?",
                "식사가 4주째 같은데 어떻게 보시나요?"));

        coordinator.generate(new QuestionGenerationRequested(caseId, generationId));

        ArgumentCaptor<QuestionRewritePrompt.Prompt> prompts =
            ArgumentCaptor.forClass(QuestionRewritePrompt.Prompt.class);
        verify(client, org.mockito.Mockito.times(3)).complete(prompts.capture());
        assertThat(prompts.getAllValues().get(0).systemMessage()).doesNotContain("직전 응답");
        assertThat(prompts.getAllValues().get(1).systemMessage()).contains("QUESTION_MARK");
        assertThat(prompts.getAllValues().get(2).systemMessage()).contains("NUMBER_TOKENS");
        assertThat(prompts.getAllValues().subList(1, 3)).allSatisfy(prompt -> {
            assertThat(prompt.systemMessage())
                .doesNotContain("REJECTED_SENTINEL", questionMarkRejected,
                    questionMarkRejectedResponse, numberTokensRejected,
                    numberTokensRejectedResponse);
            assertThat(prompt.userMessage())
                .doesNotContain("REJECTED_SENTINEL", questionMarkRejected,
                    questionMarkRejectedResponse, numberTokensRejected,
                    numberTokensRejectedResponse);
        });

        ArgumentCaptor<QuestionCacheBody> body = ArgumentCaptor.forClass(QuestionCacheBody.class);
        verify(writer).markDone(org.mockito.ArgumentMatchers.eq(caseId),
            org.mockito.ArgumentMatchers.eq(generationId), body.capture());
        assertThat(body.getValue().questions()).allSatisfy(question -> {
            assertThat(question.source()).isEqualTo(QuestionCacheBody.SOURCE_LLM);
            assertThat(question.sentence()).isNotEqualTo(question.templateSentence());
        });
        verify(writer, never()).markFailed(any(), any());
    }

    @Test
    void threeProviderFailuresLeaveTheTemplateBodyAndMarkFailed() {
        when(client.complete(any())).thenThrow(new LlmClientException(LlmFailureCode.TIMEOUT));

        coordinator.generate(new QuestionGenerationRequested(caseId, generationId));

        verify(client, org.mockito.Mockito.times(3)).complete(any());
        verify(writer).markFailed(caseId, generationId);
        verify(writer, never()).markDone(any(), any(), any());
    }

    @Test
    void oneUnsafeQuestionRejectsTheEntireBatch() {
        when(client.complete(any())).thenReturn(
            response("3주 중 2주인가요?", "4주째 재활이 필요한가요?"));

        coordinator.generate(new QuestionGenerationRequested(caseId, generationId));

        verify(client, org.mockito.Mockito.times(3)).complete(any());
        verify(writer).markFailed(caseId, generationId);
        verify(writer, never()).markDone(any(), any(), any());
    }

    @Test
    void staleGenerationMakesNoProviderCall() {
        when(caches.findByCaseId(caseId)).thenReturn(Optional.empty());

        coordinator.generate(new QuestionGenerationRequested(caseId, generationId));

        verifyNoInteractions(client);
        verifyNoInteractions(writer);
    }

    @Test
    void generationThatBecomesStaleBetweenAttemptsStopsWithoutFinalWrite() {
        when(caches.existsByCaseIdAndGenerationIdAndStatus(
            caseId, generationId, QuestionCacheStatus.LLM_PENDING)).thenReturn(true, false);
        when(client.complete(any())).thenReturn(
            response("3주 중 2주인가요??", "4주째인가요?"));

        coordinator.generate(new QuestionGenerationRequested(caseId, generationId));

        verify(client).complete(any());
        verifyNoInteractions(writer);
    }

    @Test
    void logsContainMetadataButNoCaseOrQuestionContent(CapturedOutput output) {
        when(client.complete(any())).thenReturn(
            response("SENSITIVE_SENTINEL 재활 3주 중 2주인가요?", "4주째인가요?"));

        coordinator.generate(new QuestionGenerationRequested(caseId, generationId));

        assertThat(output).contains("qwen3:4b-q8_0", generationId.toString(), "FORBIDDEN_WORD");
        assertThat(output).doesNotContain(caseId.toString(), "SENSITIVE_SENTINEL",
            templates.questions().get(0).templateSentence());
    }

    private static QuestionCacheBody.Q question(int rank, String template) {
        return new QuestionCacheBody.Q(rank, "TYPE", List.of(), null,
            template, template, QuestionCacheBody.SOURCE_TEMPLATE);
    }

    private static String response(String rankOne, String rankTwo) {
        return "{\"questions\":[{\"rank\":1,\"sentence\":\"" + rankOne
            + "\"},{\"rank\":2,\"sentence\":\"" + rankTwo + "\"}]}";
    }
}
