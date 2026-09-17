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
import nextvisit.api.snapshots.Snapshot;
import nextvisit.api.snapshots.SnapshotBody;
import nextvisit.api.snapshots.SnapshotRepository;
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
    @Autowired SnapshotRepository snapshots;
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
        stubSuccessfulSynthesis(entered);

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
            .allSatisfy(question -> {
                assertThat(question.source()).isEqualTo(QuestionCacheBody.SOURCE_LLM);
                assertThat(question.origin()).isEqualTo(QuestionCacheBody.ORIGIN_LLM);
                assertThat(question.type()).isEqualTo(QuestionCacheBody.TYPE_SYNTHESIS);
                assertThat(question.basisOrEmpty().noteWeeks()).isNotEmpty();
            });
    }

    @Test
    void demoAndWeeklyHttpTransactionsReturnWhileSlowLlmRuns() throws Exception {
        CountDownLatch demoEntered = new CountDownLatch(1);
        release.set(new CountDownLatch(1));
        stubSuccessfulSynthesis(demoEntered);

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
        stubSuccessfulSynthesis(weeklyEntered);
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
        stubSuccessfulSynthesis(committedCall);

        transactions.executeWithoutResult(status -> {
            questions.refresh(committedCase);
            assertThat(committedCall.getCount()).isEqualTo(1);
        });
        assertThat(committedCall.await(2, TimeUnit.SECONDS)).isTrue();
        awaitStatus(committedCase, QuestionCacheStatus.LLM_DONE);

        reset(client);
        UUID rolledBackCase = seededCase();
        CountDownLatch rolledBackCall = new CountDownLatch(1);
        stubSuccessfulSynthesis(rolledBackCall);
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
            String response = synthesized(invocation.getArgument(0),
                call == 1 ? Synthesis.NAP : Synthesis.SHOULDER);
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
    void caseWithoutAnyCaregiverNoteStaysReadyAndMakesNoProviderCall() throws Exception {
        Onboarded onboarded = onboardDefault(mvc, mapper);
        UUID caseId = UUID.fromString(onboarded.caseId());
        CountDownLatch called = new CountDownLatch(1);
        stubSuccessfulSynthesis(called);

        QuestionCacheBody body = questions.refresh(caseId);

        assertThat(body.questions()).isEmpty();
        assertThat(caches.findByCaseId(caseId).orElseThrow().getStatus())
            .isEqualTo(QuestionCacheStatus.READY);
        assertThat(applicationEvents.stream(QuestionGenerationRequested.class)
            .filter(event -> event.caseId().equals(caseId))
            .count()).isZero();
        assertThat(called.await(300, TimeUnit.MILLISECONDS)).isFalse();
    }

    /** 2026-09-17 정리 설계 3.1: 부르는 조건은 '질문이 있느냐'가 아니라 '보호자 원문이 있느냐'다. */
    @Test
    void questionsWithoutAnyCaregiverNoteStayReadyAndMakeNoProviderCall() throws Exception {
        UUID caseId = seededCase();
        stripEveryCaregiverNote(caseId);
        CountDownLatch called = new CountDownLatch(1);
        stubSuccessfulSynthesis(called);

        QuestionCacheBody body = questions.refresh(caseId);

        assertThat(body.questions()).isNotEmpty();
        assertThat(caches.findByCaseId(caseId).orElseThrow().getStatus())
            .isEqualTo(QuestionCacheStatus.READY);
        assertThat(applicationEvents.stream(QuestionGenerationRequested.class)
            .filter(event -> event.caseId().equals(caseId))
            .count()).isZero();
        assertThat(called.await(300, TimeUnit.MILLISECONDS)).isFalse();
    }

    @Test
    void aCaregiverNoteWithoutAnyQuestionStillSchedulesSynthesis() throws Exception {
        Onboarded onboarded = onboardDefault(mvc, mapper);
        UUID caseId = UUID.fromString(onboarded.caseId());
        CountDownLatch entered = new CountDownLatch(1);
        release.set(new CountDownLatch(1));
        stubSuccessfulSynthesis(entered);
        clock.advanceDays(7);

        mvc.perform(putJson(onboarded.token(), "/me/weeks/2", mapper, weeklyWithNote()))
            .andExpect(status().isOk());

        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(questions.current(caseId).orElseThrow().questions()).isEmpty();
        assertThat(caches.findByCaseId(caseId).orElseThrow().getStatus())
            .isEqualTo(QuestionCacheStatus.LLM_PENDING);
        assertThat(applicationEvents.stream(QuestionGenerationRequested.class)
            .filter(event -> event.caseId().equals(caseId))
            .count()).isEqualTo(1);
    }

    /** 시드 케이스에서 자유 기록만 지운다 — 질문은 그대로 남고 보호자 원문만 사라진다. */
    private void stripEveryCaregiverNote(UUID caseId) {
        transactions.executeWithoutResult(status -> {
            for (Snapshot snapshot : snapshots.findByCaseIdOrderByWeekAsc(caseId)) {
                SnapshotBody body;
                try {
                    body = mapper.readValue(snapshot.getBody(), SnapshotBody.class);
                    if (body.freeNote() == null) {
                        continue;
                    }
                    snapshot.overwrite(snapshot.getKind(), snapshot.isNoChange(), snapshot.getAuthorId(),
                        mapper.writeValueAsString(new SnapshotBody(body.items(), body.painSignal(), body.sleep(), null)),
                        snapshot.getRecordedAt());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                snapshots.save(snapshot);
            }
        });
    }

    private UUID seededCase() throws Exception {
        Onboarded onboarded = onboardDefault(mvc, mapper);
        UUID caseId = UUID.fromString(onboarded.caseId());
        seedWriter.write(cases.findById(caseId).orElseThrow(),
            guardians.findByCaseId(caseId).get(0));
        return caseId;
    }

    private static Map<String, Object> weeklyWithNote() {
        Map<String, Object> body = weeklyNoChange();
        body.put("freeNote", Map.of("text", "합성 기록: 오후에 어깨를 자꾸 만지신다", "timeTag", "AFTERNOON"));
        return body;
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

    private void stubSuccessfulSynthesis(CountDownLatch entered) {
        when(client.complete(any())).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.get().await(5, TimeUnit.SECONDS)) {
                throw new LlmClientException(LlmFailureCode.TIMEOUT);
            }
            return synthesized(invocation.getArgument(0), Synthesis.SHOULDER);
        });
    }

    /**
     * 정리 경로의 성공 응답을 흉내낸다. 근거는 프롬프트에 실제로 들어간 변화 id와 보호자 기록 주차에서만
     * 고르므로, 검증기가 근거 없는 인용을 막는 규칙에 걸리지 않는다.
     */
    private String synthesized(LlmPrompt prompt, Synthesis variant) throws Exception {
        JsonNode payload = mapper.readTree(prompt.userMessage());
        ObjectNode output = mapper.createObjectNode();
        ArrayNode questions = output.putArray("questions");
        ObjectNode question = questions.addObject();
        question.put("sentence", variant.sentence());
        ArrayNode detections = question.putArray("detections");
        if (!payload.get("detections").isEmpty()) {
            detections.add(payload.get("detections").get(0).get("id").asText());
        }
        ArrayNode noteWeeks = question.putArray("noteWeeks");
        noteWeeks.add(payload.get("notes").get(0).get("week").asInt());
        return mapper.writeValueAsString(output);
    }

    /** 검증기를 통과하는 두 문장. 숫자를 쓰지 않아 근거 밖 숫자 규칙에 걸리지 않는다. */
    private enum Synthesis {
        SHOULDER("오후마다 어깨를 자꾸 만지시는데 어떤 점을 살펴보면 좋을까요?"),
        NAP("낮잠에서 깨신 뒤 어깨를 감싸시는데 선생님께서는 어떻게 보시나요?");

        private final String sentence;

        Synthesis(String sentence) {
            this.sentence = sentence;
        }

        String sentence() {
            return sentence;
        }
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
