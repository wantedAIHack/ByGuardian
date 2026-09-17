package nextvisit.api.llm;

import static nextvisit.api.ApiTestSupport.onboardDefault;
import static nextvisit.api.ApiTestSupport.json;
import static nextvisit.api.ApiTestSupport.putJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import nextvisit.api.ApiTestSupport.Onboarded;
import nextvisit.api.MutableClock;
import nextvisit.api.TestClockConfig;
import nextvisit.api.auth.GuardianRepository;
import nextvisit.api.cases.CaseRepository;
import nextvisit.api.demo.DemoSeedWriter;
import nextvisit.api.questions.QuestionCacheBody;
import nextvisit.api.questions.QuestionCacheRepository;
import nextvisit.api.questions.QuestionCacheStatus;
import nextvisit.api.questions.QuestionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "nextvisit.llm.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
class QuestionAsyncIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MutableClock clock;
    @Autowired CaseRepository cases;
    @Autowired GuardianRepository guardians;
    @Autowired DemoSeedWriter seedWriter;
    @Autowired QuestionService questions;
    @Autowired QuestionCacheRepository caches;
    @Autowired TransactionTemplate transactions;
    @Autowired @Qualifier("llmTaskExecutor") ThreadPoolTaskExecutor executor;
    @Autowired ApplicationEvents applicationEvents;
    @MockitoBean LlmClient client;

    private final AtomicReference<CountDownLatch> release = new AtomicReference<>();
    private final Queue<CountDownLatch> cleanupReleases = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void resetClient() {
        reset(client);
        release.set(new CountDownLatch(0));
        cleanupReleases.clear();
        clock.set(TestClockConfig.DEFAULT_TODAY);
    }

    @AfterEach
    void unblockWorkerAndWaitUntilIdle() throws Exception {
        release.get().countDown();
        cleanupReleases.forEach(CountDownLatch::countDown);
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (executor.getActiveCount() == 0
                && executor.getThreadPoolExecutor().getQueue().isEmpty()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("llm executor did not become idle");
    }

    @Test
    void refreshReturnsWhileSlowLlmRunsAndPendingBodyIsReadable() throws Exception {
        UUID caseId = seededCase();
        CountDownLatch entered = new CountDownLatch(1);
        release.set(new CountDownLatch(1));
        stubSuccessfulRewrite(entered);

        QuestionCacheBody immediate = assertTimeout(Duration.ofSeconds(1),
            () -> questions.refresh(caseId));

        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(applicationEvents.stream(QuestionGenerationRequested.class)
            .filter(event -> event.caseId().equals(caseId))
            .count()).isEqualTo(1);
        assertThat(caches.findByCaseId(caseId).orElseThrow().getStatus())
            .isEqualTo(QuestionCacheStatus.LLM_PENDING);
        assertThat(immediate.questions()).allSatisfy(question -> {
            assertThat(question.source()).isEqualTo(QuestionCacheBody.SOURCE_TEMPLATE);
            assertThat(question.sentence()).isEqualTo(question.templateSentence());
        });
        assertThat(questions.current(caseId).orElseThrow()).isEqualTo(immediate);

        release.get().countDown();
        awaitStatus(caseId, QuestionCacheStatus.LLM_DONE);
        assertThat(questions.current(caseId).orElseThrow().questions())
            .allSatisfy(question -> assertThat(question.source())
                .isEqualTo(QuestionCacheBody.SOURCE_LLM));
    }

    @Test
    void demoAndWeeklyHttpTransactionsReturnWhileSlowLlmRuns() throws Exception {
        CountDownLatch demoEntered = new CountDownLatch(1);
        release.set(new CountDownLatch(1));
        stubSuccessfulRewrite(demoEntered);

        MvcResult demo = assertTimeout(Duration.ofSeconds(1), () ->
            mvc.perform(post("/demo"))
                .andExpect(status().isCreated())
                .andReturn());
        JsonNode created = json(mapper, demo);
        UUID caseId = UUID.fromString(created.get("caseId").asText());
        String token = created.get("guardianToken").asText();

        assertThat(demoEntered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(caches.findByCaseId(caseId).orElseThrow().getStatus())
            .isEqualTo(QuestionCacheStatus.LLM_PENDING);
        release.get().countDown();
        awaitStatus(caseId, QuestionCacheStatus.LLM_DONE);

        reset(client);
        CountDownLatch weeklyEntered = new CountDownLatch(1);
        release.set(new CountDownLatch(1));
        stubSuccessfulRewrite(weeklyEntered);
        clock.advanceDays(7);

        assertTimeout(Duration.ofSeconds(1), () ->
            mvc.perform(putJson(token, "/me/weeks/7", mapper, weeklyNoChange()))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(weeklyEntered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(caches.findByCaseId(caseId).orElseThrow().getStatus())
            .isEqualTo(QuestionCacheStatus.LLM_PENDING);
        release.get().countDown();
        awaitStatus(caseId, QuestionCacheStatus.LLM_DONE);
    }

    @Test
    void outerCommitTriggersWorkButOuterRollbackDoesNot() throws Exception {
        UUID committedCase = seededCase();
        CountDownLatch committedCall = new CountDownLatch(1);
        stubSuccessfulRewrite(committedCall);

        transactions.executeWithoutResult(status -> {
            questions.refresh(committedCase);
            assertThat(committedCall.getCount()).isEqualTo(1);
        });
        assertThat(committedCall.await(2, TimeUnit.SECONDS)).isTrue();
        awaitStatus(committedCase, QuestionCacheStatus.LLM_DONE);

        reset(client);
        UUID rolledBackCase = seededCase();
        CountDownLatch rolledBackCall = new CountDownLatch(1);
        stubSuccessfulRewrite(rolledBackCall);
        transactions.executeWithoutResult(status -> {
            questions.refresh(rolledBackCase);
            status.setRollbackOnly();
        });
        assertThat(rolledBackCall.await(300, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(caches.findByCaseId(rolledBackCase)).isEmpty();
    }

    @Test
    void slowOlderGenerationCannotFinalizeOverANewerRefresh() throws Exception {
        UUID caseId = seededCase();
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch firstRelease = cleanupRelease();
        CountDownLatch secondEntered = new CountDownLatch(1);
        CountDownLatch secondRelease = cleanupRelease();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> olderResponse = new AtomicReference<>();
        AtomicReference<String> newerResponse = new AtomicReference<>();
        when(client.complete(any())).thenAnswer(invocation -> {
            int call = calls.incrementAndGet();
            CountDownLatch entered = call == 1 ? firstEntered : secondEntered;
            CountDownLatch callRelease = call == 1 ? firstRelease : secondRelease;
            entered.countDown();
            if (!callRelease.await(5, TimeUnit.SECONDS)) {
                throw new LlmClientException(LlmFailureCode.TIMEOUT);
            }
            String response = successfulRewrite(invocation.getArgument(0),
                call == 1 ? SurfaceForm.CONNECTED : SurfaceForm.UNCHANGED);
            (call == 1 ? olderResponse : newerResponse).set(response);
            return response;
        });

        questions.refresh(caseId);
        assertThat(firstEntered.await(2, TimeUnit.SECONDS)).isTrue();
        UUID olderGeneration = caches.findByCaseId(caseId).orElseThrow().getGenerationId();

        QuestionCacheBody newerTemplates = questions.refresh(caseId);
        UUID newerGeneration = caches.findByCaseId(caseId).orElseThrow().getGenerationId();
        assertThat(newerGeneration).isNotEqualTo(olderGeneration);

        firstRelease.countDown();
        assertThat(secondEntered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(caches.findByCaseId(caseId).orElseThrow()).satisfies(cache -> {
            assertThat(cache.getGenerationId()).isEqualTo(newerGeneration);
            assertThat(cache.getStatus()).isEqualTo(QuestionCacheStatus.LLM_PENDING);
        });
        assertThat(questions.current(caseId).orElseThrow()).isEqualTo(newerTemplates);
        assertThat(newerTemplates.questions()).allSatisfy(question -> {
            assertThat(question.source()).isEqualTo(QuestionCacheBody.SOURCE_TEMPLATE);
            assertThat(question.sentence()).isEqualTo(question.templateSentence());
        });

        secondRelease.countDown();
        awaitStatus(caseId, QuestionCacheStatus.LLM_DONE);
        assertThat(caches.findByCaseId(caseId).orElseThrow().getGenerationId())
            .isEqualTo(newerGeneration);
        assertThat(calls).hasValue(2);
        assertThat(olderResponse.get()).isNotEqualTo(newerResponse.get());
        assertThat(questions.current(caseId).orElseThrow().questions())
            .allSatisfy(question -> {
                assertThat(question.source()).isEqualTo(QuestionCacheBody.SOURCE_LLM);
                assertThat(question.sentence()).isEqualTo(question.templateSentence());
            });
    }

    @Test
    void emptyQuestionSetStaysReadyAndMakesNoProviderCall() throws Exception {
        Onboarded onboarded = onboardDefault(mvc, mapper);
        UUID caseId = UUID.fromString(onboarded.caseId());
        CountDownLatch called = new CountDownLatch(1);
        stubSuccessfulRewrite(called);

        QuestionCacheBody body = questions.refresh(caseId);

        assertThat(body.questions()).isEmpty();
        assertThat(caches.findByCaseId(caseId).orElseThrow().getStatus())
            .isEqualTo(QuestionCacheStatus.READY);
        assertThat(applicationEvents.stream(QuestionGenerationRequested.class)
            .filter(event -> event.caseId().equals(caseId))
            .count()).isZero();
        assertThat(called.await(300, TimeUnit.MILLISECONDS)).isFalse();
    }

    private UUID seededCase() throws Exception {
        Onboarded onboarded = onboardDefault(mvc, mapper);
        UUID caseId = UUID.fromString(onboarded.caseId());
        seedWriter.write(cases.findById(caseId).orElseThrow(),
            guardians.findByCaseId(caseId).get(0));
        return caseId;
    }

    private static Map<String, Object> weeklyNoChange() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("noChange", true);
        body.put("changedItems", Map.of());
        body.put("painSignal", Map.of());
        body.put("sleep", 1);
        body.put("freeNote", null);
        return body;
    }

    private void stubSuccessfulRewrite(CountDownLatch entered) {
        when(client.complete(any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.get().await(5, TimeUnit.SECONDS)) {
                throw new LlmClientException(LlmFailureCode.TIMEOUT);
            }
            return successfulRewrite(invocation.getArgument(0), SurfaceForm.UNCHANGED);
        });
    }

    private String successfulRewrite(LlmPrompt prompt,
                                     SurfaceForm surfaceForm) throws Exception {
        JsonNode input = mapper.readTree(prompt.userMessage()).get("questions");
        ObjectNode output = mapper.createObjectNode();
        ArrayNode rewritten = output.putArray("questions");
        for (JsonNode question : input) {
            String template = question.get("templateSentence").asText();
            rewritten.addObject()
                .put("rank", question.get("rank").asInt())
                .put("sentence", surfaceForm.rewrite(template));
        }
        return mapper.writeValueAsString(output);
    }

    private enum SurfaceForm {
        UNCHANGED {
            @Override
            String rewrite(String template) {
                return template;
            }
        },
        CONNECTED {
            @Override
            String rewrite(String template) {
                int seumnida = template.lastIndexOf("습니다. ");
                if (seumnida >= 0) {
                    return template.substring(0, seumnida) + "는데 "
                        + template.substring(seumnida + "습니다. ".length());
                }
                int ibnida = template.lastIndexOf("입니다. ");
                if (ibnida >= 0) {
                    return template.substring(0, ibnida) + "인데 "
                        + template.substring(ibnida + "입니다. ".length());
                }
                throw new AssertionError("fixture template has no permitted connected ending: " + template);
            }
        };

        abstract String rewrite(String template);
    }

    private CountDownLatch cleanupRelease() {
        CountDownLatch latch = new CountDownLatch(1);
        cleanupReleases.add(latch);
        return latch;
    }

    private void awaitStatus(UUID caseId, QuestionCacheStatus expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (caches.findByCaseId(caseId)
                .map(cache -> cache.getStatus() == expected)
                .orElse(false)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("cache did not reach " + expected);
    }
}
