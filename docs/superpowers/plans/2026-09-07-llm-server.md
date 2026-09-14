# LLM Server, Spring Integration, and Laptop Delivery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a fail-open LLM sentence-rewriting path in Spring, a pinned Ollama Docker stack, and a private-repository-safe GitHub Actions delivery path that remains disabled until the Ubuntu gaming laptop is ready.

**Architecture:** The deterministic engine continues to select at most three questions and writes safe template sentences to `question_cache`; Spring publishes an `AFTER_COMMIT` event and one bounded worker calls Ollama's OpenAI-compatible endpoint, validates the entire batch, then atomically replaces only the still-current generation. Docker Compose owns Ollama, model initialization, GPU, and Cloudflare Tunnel variants; GitHub-hosted runners perform CI while a repository-scoped `self-hosted, linux, llm` runner performs guarded `main` deployments later.

**Tech Stack:** Java 21, Spring Boot 3.5.5, Spring Data JPA, Flyway, PostgreSQL/H2, Jackson, `RestClient`, JUnit 5/Mockito, Ollama 0.33.3, `qwen3:4b-q8_0`, Docker Compose v2, POSIX shell/ShellCheck, Cloudflare Tunnel, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-07-llm-server-design.md`

## Global Constraints

- Work only on branch `feat/llm-server`, which already starts from `main`; do not modify the parallel frontend checkout or any frontend path.
- Keep the deterministic engine responsible for detection and selection. The LLM may rewrite only `templateSentence` values and receives no case ID, diagnosis, free note, guardian text, or raw snapshot.
- Add no FastAPI gateway, Spring AI dependency, durable queue, free-note extraction, speech processing, or EC2 deployment work.
- Pin the server image to `ollama/ollama:0.33.3@sha256:32931b46719f673c05fdbaa81ccb26da18ea4a1c57590a754874ab28ba269eb2`, default the model to `qwen3:4b-q8_0`, set `OLLAMA_CONTEXT_LENGTH=2048`, and keep the model in the named volume rather than the image.
- Call `POST /v1/chat/completions` with `stream=false`, `temperature=0.1`, `seed=0`, `max_tokens=512`, JSON response mode, and `/no_think` in the system prompt.
- Permit at most three total attempts: the initial call plus two regeneration calls. A batch is all-success or all-template-fallback.
- Default `nextvisit.llm.enabled` to `false`; existing local and test runs must not require Ollama.
- Keep `/health` dependent on the database only; an unavailable LLM must never turn product health into HTTP 503.
- Use one worker, a queue capacity of 32, and a five-second shutdown wait. Queue rejection must not fail the user request.
- Every cache refresh gets a new UUID `generation_id`; a worker may finalize only a row still matching `(case_id, generation_id, LLM_PENDING)`.
- Never log prompts, response bodies, output sentences, template sentences, free notes, authorization headers, Cloudflare credentials, or case IDs from LLM components. Allowed fields are model, generation ID, elapsed milliseconds, attempt count, result code, and fallback rule.
- Bind the development Ollama port only to `127.0.0.1`; the final Tunnel Compose configuration must publish no host port.
- Require Docker Compose v2.24.4 or later because the final override uses the `!reset` tag. Pin third-party container images by manifest digest and GitHub Actions by full commit SHA.
- CI must not pull the 4.4 GB model or perform real inference. It may run Java tests, fake-provider tests, shell tests, Docker build, and Compose rendering.
- The private repository does not make PR code trusted: never run a pull request on the self-hosted laptop, never use `pull_request_target`, and grant Actions only `contents: read`.
- Leave `LLM_DEPLOY_ENABLED` absent or `false` until the Ubuntu/NVIDIA host, Cloudflare route, environment file, and repository-scoped runner have been manually prepared.
- Treat macOS Java, fake OpenAI, shell, Docker build, and CPU container checks as current acceptance. Record Ubuntu/NVIDIA inference, reboot recovery, and `ollama ps` GPU checks as deferred acceptance, not as a current pass claim.
- Run local Gradle commands from `backend/` with `JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home`; CI obtains Temurin 21 through `actions/setup-java`.
- Preserve the existing 112 engine tests and 59 API tests, including the three byte-for-byte demo template sentences when LLM is disabled.

---

## File and Interface Map

| Area | File | Single responsibility |
| --- | --- | --- |
| Engine safety | `backend/engine/src/main/java/nextvisit/engine/Templates.java` | Shared NFC-aware prohibited vocabulary and template safety checks |
| Cache state | `backend/api/src/main/java/nextvisit/api/questions/QuestionCacheStatus.java` | Four persistent cache states |
| Cache entity | `backend/api/src/main/java/nextvisit/api/questions/QuestionCache.java` | Current body, state, and generation identity |
| Atomic writes | `backend/api/src/main/java/nextvisit/api/questions/QuestionCacheRepository.java` | Compare-and-set completion/failure queries |
| Refresh entry point | `backend/api/src/main/java/nextvisit/api/questions/QuestionService.java` | Store template-first cache and publish generation events |
| LLM settings | `backend/api/src/main/java/nextvisit/api/llm/LlmProperties.java` | Typed environment-backed settings and bounds |
| Bean wiring | `backend/api/src/main/java/nextvisit/api/llm/LlmConfiguration.java` | Conditional HTTP client and bounded executor |
| Provider port | `backend/api/src/main/java/nextvisit/api/llm/LlmClient.java` | Provider-neutral completion interface |
| Provider failures | `backend/api/src/main/java/nextvisit/api/llm/LlmFailureCode.java` and `LlmClientException.java` | Content-free failure classification |
| Provider adapter | `backend/api/src/main/java/nextvisit/api/llm/OpenAiCompatibleLlmClient.java` | OpenAI-compatible request and envelope parsing |
| Prompt | `backend/api/src/main/java/nextvisit/api/llm/QuestionRewritePrompt.java` | Minimal rank/template JSON and fixed safety instruction |
| Output guard | `backend/api/src/main/java/nextvisit/api/llm/QuestionOutputGuard.java` | Strict whole-batch structural and language validation |
| Event | `backend/api/src/main/java/nextvisit/api/llm/QuestionGenerationRequested.java` | `(caseId, generationId)` committed-work identity |
| Result transaction | `backend/api/src/main/java/nextvisit/api/llm/QuestionGenerationResultWriter.java` | Short `REQUIRES_NEW` compare-and-set finalization |
| Worker | `backend/api/src/main/java/nextvisit/api/llm/QuestionGenerationCoordinator.java` | Retry, validate, all-or-none rewrite, and metadata-only logs |
| Dispatch | `backend/api/src/main/java/nextvisit/api/llm/QuestionGenerationDispatcher.java` | `AFTER_COMMIT` bounded submission and rejection fallback |
| DB migration | `backend/api/src/main/resources/db/migration/{postgresql,h2}/V2__question_cache_generation.sql` | Nullable UUID generation column for existing rows |
| CPU container | `infra/llm/Dockerfile`, `compose.yml` | Pinned Ollama image, health, volume, and model init |
| Production variants | `infra/llm/compose.gpu.yml`, `compose.tunnel.yml` | NVIDIA reservation and final no-port Tunnel service |
| Operations | `infra/llm/scripts/*.sh`, `infra/llm/tests/*.sh`, `infra/llm/README.md` | Readiness, idempotent pull, private smoke, host checks, and deferred setup |
| Automation | `.github/workflows/llm-ci.yml`, `.github/workflows/llm-deploy.yml` | Hosted CI and disabled-by-default laptop deployment |
| Project docs | `README.md`, `backend/README.md` | Implemented behavior, local usage, and remaining hardware acceptance |

The backend tasks form one vertical slice rather than separate subprojects: cache state, the provider contract, and async finalization are not independently user-visible. The container and workflow tasks retain independent review gates but share the exact Compose project and smoke contracts from this plan.

### Task 1: Expand the shared prohibited vocabulary

**Files:**
- Modify: `README.md`
- Modify: `backend/engine/src/main/java/nextvisit/engine/Templates.java`
- Modify: `backend/engine/src/test/java/nextvisit/engine/TemplatesTest.java`
- Modify: `backend/engine/src/test/java/nextvisit/engine/conformance/ReadmeConformanceTest.java`

**Interfaces:**
- Consumes: the exact README §8 prohibited-term list, `Templates.containsForbiddenWord(String)`, and `Templates.isSafe(String)`.
- Produces: `Templates.FORBIDDEN` containing the existing 19 entries plus `재활`, `치료`, `낙상`, `점수`, `처방`, `운동`, `진단`, `기능검사`, `병원에 가`, `받으셔야`, and `하셔야`; Task 5 calls the same NFC-aware check.

- [ ] **Step 1: Add failing tests for every new term and update the README conformance fixture**

Add these imports and test method to `TemplatesTest`:

```java
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@ParameterizedTest
@ValueSource(strings = {
    "재활", "치료", "낙상", "점수", "처방", "운동", "진단", "기능검사",
    "병원에 가", "받으셔야", "하셔야"
})
void isSafeRejectsTermsThatAnLlmMustNotIntroduce(String term) {
    assertFalse(Templates.isSafe(term + " 관련해서 확인할까요?"), term);
}
```

Keep `everyTypeRendersSafeQuestion()` unchanged; it is the regression proof that no approved engine template collides with the expanded list.

In `ReadmeConformanceTest.section8_guardrail()`, replace `readmeList` with the same complete list that Task 1 will put in `Templates.FORBIDDEN`:

```java
List<String> readmeList = List.of("개선", "악화", "호전", "위험", "정상", "비정상", "회복",
    "좋아지", "좋아졌", "나빠지", "나빠졌", "나아지", "나아졌",
    "좋아져", "나빠져", "나아져", "좋아짐", "나빠짐", "나아짐",
    "재활", "치료", "낙상", "점수", "처방", "운동", "진단", "기능검사",
    "병원에 가", "받으셔야", "하셔야");
```

- [ ] **Step 2: Update README §8, the documentation source of truth**

Replace the prohibited-vocabulary line in the §8 guardrail block with this exact list, while retaining its NFC-normalization explanation:

```text
2. 금지 어휘 필터 → 개선, 악화, 호전, 위험, 정상, 비정상, 회복, 좋아지, 좋아졌, 나빠지, 나빠졌, 나아지, 나아졌, 좋아져, 나빠져, 나아져, 좋아짐, 나빠짐, 나아짐, 재활, 치료, 낙상, 점수, 처방, 운동, 진단, 기능검사, 병원에 가, 받으셔야, 하셔야 가 포함되면 재생성
```

- [ ] **Step 3: Run the focused tests and observe the expected failure**

Run:

```bash
cd backend
env JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home ./gradlew :engine:test --tests nextvisit.engine.TemplatesTest --tests nextvisit.engine.conformance.ReadmeConformanceTest
```

Expected: `isSafeRejectsTermsThatAnLlmMustNotIntroduce` and the README conformance equality fail because the new strings are still absent from `Templates.FORBIDDEN`; existing template assertions still pass.

- [ ] **Step 4: Add the exact vocabulary to the shared list**

Replace the `List.of` assignment with:

```java
public static final List<String> FORBIDDEN =
    List.of("개선", "악화", "호전", "위험", "정상", "비정상", "회복",
        "좋아지", "좋아졌", "나빠지", "나빠졌", "나아지", "나아졌",
        "좋아져", "나빠져", "나아져", "좋아짐", "나빠짐", "나아짐",
        "재활", "치료", "낙상", "점수", "처방", "운동", "진단", "기능검사",
        "병원에 가", "받으셔야", "하셔야");
```

- [ ] **Step 5: Run the engine regression suite**

Run:

```bash
cd backend
env JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home ./gradlew :engine:test
```

Expected: all engine tests pass, including every `DetectionType` template under the expanded list.

- [ ] **Step 6: Commit the safety boundary**

```bash
git add README.md backend/engine/src/main/java/nextvisit/engine/Templates.java backend/engine/src/test/java/nextvisit/engine/TemplatesTest.java backend/engine/src/test/java/nextvisit/engine/conformance/ReadmeConformanceTest.java
git commit -m "feat: LLM 금지 표현 확장"
```

### Task 2: Add cache generations and atomic finalization

**Files:**
- Create: `backend/api/src/main/java/nextvisit/api/questions/QuestionCacheStatus.java`
- Modify: `backend/api/src/main/java/nextvisit/api/questions/QuestionCache.java`
- Modify: `backend/api/src/main/java/nextvisit/api/questions/QuestionCacheRepository.java`
- Modify: `backend/api/src/main/java/nextvisit/api/questions/QuestionService.java`
- Create: `backend/api/src/main/resources/db/migration/postgresql/V2__question_cache_generation.sql`
- Create: `backend/api/src/main/resources/db/migration/h2/V2__question_cache_generation.sql`
- Modify: `backend/api/src/test/java/nextvisit/api/PersistenceTest.java`
- Modify: `backend/api/src/test/java/nextvisit/api/questions/QuestionServiceTest.java`

**Interfaces:**
- Consumes: existing `question_cache` row and `QuestionService.refresh(UUID)` behavior.
- Produces: `QuestionCacheStatus { READY, LLM_PENDING, LLM_DONE, LLM_FAILED }`; `QuestionCache#getGenerationId()`; `QuestionCache#update(int, QuestionCacheStatus, UUID, String, Instant)`; `QuestionCacheRepository#existsByCaseIdAndGenerationIdAndStatus(UUID, UUID, QuestionCacheStatus)`; `completeGenerationIfPending(UUID, UUID, QuestionCacheStatus, QuestionCacheStatus, String, Instant)`; and `failGenerationIfPending(UUID, UUID, QuestionCacheStatus, QuestionCacheStatus, Instant)` for Tasks 7 and 8.

- [ ] **Step 1: Write persistence tests for UUID round-trip and stale-write rejection**

Replace `questionCacheIsOnePerCase()` in `PersistenceTest` with these tests and add imports for `QuestionCacheStatus` and `assertFalse`:

```java
@Test
void questionCachePersistsTypedStatusAndGeneration() {
    CaseEntity kase = newCase("rc-" + UUID.randomUUID());
    UUID generationId = UUID.randomUUID();
    caches.saveAndFlush(new QuestionCache(kase.getId(), 3, QuestionCacheStatus.LLM_PENDING,
        generationId, "{\"questions\":[]}", Instant.now()));

    QuestionCache loaded = caches.findByCaseId(kase.getId()).orElseThrow();
    assertEquals(QuestionCacheStatus.LLM_PENDING, loaded.getStatus());
    assertEquals(generationId, loaded.getGenerationId());

    UUID nextGeneration = UUID.randomUUID();
    loaded.update(4, QuestionCacheStatus.READY, nextGeneration,
        "{\"questions\":[1]}", Instant.now());
    caches.saveAndFlush(loaded);
    assertEquals(nextGeneration, caches.findByCaseId(kase.getId()).orElseThrow().getGenerationId());
}

@Test
void onlyTheCurrentPendingGenerationCanFinish() {
    CaseEntity kase = newCase("rc-" + UUID.randomUUID());
    UUID currentGeneration = UUID.randomUUID();
    String templates = "{\"questions\":[]}";
    caches.saveAndFlush(new QuestionCache(kase.getId(), 3, QuestionCacheStatus.LLM_PENDING,
        currentGeneration, templates, Instant.parse("2026-09-07T00:00:00Z")));

    int stale = caches.completeGenerationIfPending(kase.getId(), UUID.randomUUID(),
        QuestionCacheStatus.LLM_PENDING, QuestionCacheStatus.LLM_DONE,
        "{\"questions\":[\"stale\"]}", Instant.parse("2026-09-07T00:00:01Z"));
    assertEquals(0, stale);
    assertEquals(templates, caches.findByCaseId(kase.getId()).orElseThrow().getBody());

    int completed = caches.completeGenerationIfPending(kase.getId(), currentGeneration,
        QuestionCacheStatus.LLM_PENDING, QuestionCacheStatus.LLM_DONE,
        "{\"questions\":[\"current\"]}", Instant.parse("2026-09-07T00:00:02Z"));
    assertEquals(1, completed);
    assertEquals(QuestionCacheStatus.LLM_DONE, caches.findByCaseId(kase.getId()).orElseThrow().getStatus());

    int secondFinalization = caches.failGenerationIfPending(kase.getId(), currentGeneration,
        QuestionCacheStatus.LLM_PENDING, QuestionCacheStatus.LLM_FAILED,
        Instant.parse("2026-09-07T00:00:03Z"));
    assertEquals(0, secondFinalization);
    assertFalse(caches.existsByCaseIdAndGenerationIdAndStatus(
        kase.getId(), currentGeneration, QuestionCacheStatus.LLM_PENDING));
}
```

- [ ] **Step 2: Run the persistence test and observe the compile failure**

Run:

```bash
cd backend
env JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home ./gradlew :api:test --tests nextvisit.api.PersistenceTest
```

Expected: test compilation fails because `QuestionCacheStatus`, generation methods, and compare-and-set repository methods do not exist.

- [ ] **Step 3: Add both V2 migrations**

Use this exact statement in each vendor file:

```sql
alter table question_cache add column generation_id uuid;
```

The column remains nullable so databases containing V1 rows migrate without a fabricated generation.

- [ ] **Step 4: Add typed state and generation identity to the entity**

Create `QuestionCacheStatus.java`:

```java
package nextvisit.api.questions;

public enum QuestionCacheStatus {
    READY,
    LLM_PENDING,
    LLM_DONE,
    LLM_FAILED
}
```

In `QuestionCache`, add `EnumType`/`Enumerated` imports, replace the `String status` field, add `generationId`, and use these exact constructor/update/getter signatures:

```java
@Enumerated(EnumType.STRING)
@Column(nullable = false)
private QuestionCacheStatus status;

@Column(name = "generation_id")
private UUID generationId;

public QuestionCache(UUID caseId, int week, QuestionCacheStatus status, UUID generationId,
                     String body, Instant generatedAt) {
    this.id = UUID.randomUUID();
    this.caseId = caseId;
    this.week = week;
    this.status = status;
    this.generationId = generationId;
    this.body = body;
    this.generatedAt = generatedAt;
}

public void update(int week, QuestionCacheStatus status, UUID generationId,
                   String body, Instant generatedAt) {
    this.week = week;
    this.status = status;
    this.generationId = generationId;
    this.body = body;
    this.generatedAt = generatedAt;
}

public QuestionCacheStatus getStatus() { return status; }
public UUID getGenerationId() { return generationId; }
```

Remove the old `String` constructor, updater, and getter so every caller uses the typed contract.

- [ ] **Step 5: Add compare-and-set repository methods**

Replace `QuestionCacheRepository.java` with:

```java
package nextvisit.api.questions;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface QuestionCacheRepository extends JpaRepository<QuestionCache, UUID> {
    Optional<QuestionCache> findByCaseId(UUID caseId);

    boolean existsByCaseIdAndGenerationIdAndStatus(
        UUID caseId, UUID generationId, QuestionCacheStatus status);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
        update QuestionCache c
           set c.status = :done, c.body = :body, c.generatedAt = :generatedAt
         where c.caseId = :caseId
           and c.generationId = :generationId
           and c.status = :pending
        """)
    int completeGenerationIfPending(
        @Param("caseId") UUID caseId,
        @Param("generationId") UUID generationId,
        @Param("pending") QuestionCacheStatus pending,
        @Param("done") QuestionCacheStatus done,
        @Param("body") String body,
        @Param("generatedAt") Instant generatedAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("""
        update QuestionCache c
           set c.status = :failed, c.generatedAt = :generatedAt
         where c.caseId = :caseId
           and c.generationId = :generationId
           and c.status = :pending
        """)
    int failGenerationIfPending(
        @Param("caseId") UUID caseId,
        @Param("generationId") UUID generationId,
        @Param("pending") QuestionCacheStatus pending,
        @Param("failed") QuestionCacheStatus failed,
        @Param("generatedAt") Instant generatedAt);
}
```

- [ ] **Step 6: Keep the current refresh behavior while assigning a fresh generation**

In `QuestionService`, remove `STATUS_READY`. Immediately before the upsert create `UUID generationId = UUID.randomUUID();`, then replace the upsert block with:

```java
Optional<QuestionCache> existing = caches.findByCaseId(caseId);
if (existing.isPresent()) {
    existing.get().update(week, QuestionCacheStatus.READY, generationId, js, now);
    caches.save(existing.get());
} else {
    caches.save(new QuestionCache(caseId, week, QuestionCacheStatus.READY,
        generationId, js, now));
}
```

Update existing status assertions in `QuestionServiceTest` from the string to:

```java
assertEquals(QuestionCacheStatus.READY,
    caches.findByCaseId(caseId).orElseThrow().getStatus());
assertTrue(caches.findByCaseId(caseId).orElseThrow().getGenerationId() != null);
```

- [ ] **Step 7: Run API persistence and question tests**

Run:

```bash
cd backend
env JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home ./gradlew :api:test --tests nextvisit.api.PersistenceTest --tests nextvisit.api.questions.QuestionServiceTest
```

Expected: both test classes pass; the stale UUID update count is zero and the current pending UUID completes once.

- [ ] **Step 8: Commit the persistence contract**

```bash
git add backend/api/src/main/java/nextvisit/api/questions backend/api/src/main/resources/db/migration backend/api/src/test/java/nextvisit/api/PersistenceTest.java backend/api/src/test/java/nextvisit/api/questions/QuestionServiceTest.java
git commit -m "feat: 질문 캐시에 LLM 세대 상태 추가"
```

### Task 3: Bind LLM settings and the bounded worker

**Files:**
- Create: `backend/api/src/main/java/nextvisit/api/llm/LlmProperties.java`
- Create: `backend/api/src/main/java/nextvisit/api/llm/LlmConfiguration.java`
- Modify: `backend/api/src/main/resources/application.yml`
- Create: `backend/api/src/test/java/nextvisit/api/llm/LlmConfigurationTest.java`

**Interfaces:**
- Consumes: Spring Boot configuration binding and the existing validation starter.
- Produces: `LlmProperties` accessors named `enabled`, `baseUrl`, `model`, `apiKey`, `cfAccessClientId`, `cfAccessClientSecret`, `connectTimeout`, `readTimeout`, `maxAttempts`, and `maxOutputTokens`; conditional bean `@Qualifier("llmTaskExecutor") Executor` for Task 8.

- [ ] **Step 1: Write failing binding, condition, and upper-bound tests**

Create `LlmConfigurationTest.java`:

```java
package nextvisit.api.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class LlmConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(LlmConfiguration.class);

    @Test
    void defaultsAreSafeAndDoNotCreateAWorker() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            LlmProperties properties = context.getBean(LlmProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.baseUrl()).isEqualTo(URI.create("http://localhost:11434/v1"));
            assertThat(properties.model()).isEqualTo("qwen3:4b-q8_0");
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(45));
            assertThat(properties.maxAttempts()).isEqualTo(3);
            assertThat(properties.maxOutputTokens()).isEqualTo(512);
            assertThat(context.containsBean("llmTaskExecutor")).isFalse();
        });
    }

    @Test
    void enabledCreatesOneBoundedWorker() {
        contextRunner.withPropertyValues("nextvisit.llm.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            ThreadPoolTaskExecutor executor = context.getBean(
                "llmTaskExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(32);
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        });
    }

    @Test
    void moreThanThreeAttemptsIsRejectedAtStartup() {
        contextRunner.withPropertyValues("nextvisit.llm.max-attempts=4").run(context ->
            assertThat(context).hasFailed());
    }
}
```

- [ ] **Step 2: Run the configuration test and observe the compile failure**

Run:

```bash
cd backend
env JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home ./gradlew :api:test --tests nextvisit.api.llm.LlmConfigurationTest
```

Expected: test compilation fails because the `nextvisit.api.llm` configuration classes do not exist.

- [ ] **Step 3: Add the typed property record**

Create `LlmProperties.java`:

```java
package nextvisit.api.llm;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "nextvisit.llm")
public record LlmProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("http://localhost:11434/v1") URI baseUrl,
    @NotBlank @DefaultValue("qwen3:4b-q8_0") String model,
    @DefaultValue("ollama") String apiKey,
    @DefaultValue("") String cfAccessClientId,
    @DefaultValue("") String cfAccessClientSecret,
    @DefaultValue("3s") Duration connectTimeout,
    @DefaultValue("45s") Duration readTimeout,
    @Min(1) @Max(3) @DefaultValue("3") int maxAttempts,
    @Min(1) @DefaultValue("512") int maxOutputTokens
) {}
```

- [ ] **Step 4: Add the conditional bounded executor**

Create `LlmConfiguration.java`:

```java
package nextvisit.api.llm;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmProperties.class)
public class LlmConfiguration {

    @Bean(name = "llmTaskExecutor")
    @ConditionalOnProperty(name = "nextvisit.llm.enabled", havingValue = "true")
    ThreadPoolTaskExecutor llmTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(32);
        executor.setThreadNamePrefix("llm-question-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        return executor;
    }
}
```

- [ ] **Step 5: Add the environment-backed application settings**

Append this block below `nextvisit.demo` in `application.yml`, preserving the existing `timezone`, `cors`, and `demo` keys:

```yaml
  llm:
    enabled: ${NEXTVISIT_LLM_ENABLED:false}
    base-url: ${NEXTVISIT_LLM_BASE_URL:http://localhost:11434/v1}
    model: ${NEXTVISIT_LLM_MODEL:qwen3:4b-q8_0}
    api-key: ${NEXTVISIT_LLM_API_KEY:ollama}
    cf-access-client-id: ${NEXTVISIT_LLM_CF_ACCESS_CLIENT_ID:}
    cf-access-client-secret: ${NEXTVISIT_LLM_CF_ACCESS_CLIENT_SECRET:}
    connect-timeout: ${NEXTVISIT_LLM_CONNECT_TIMEOUT:3s}
    read-timeout: ${NEXTVISIT_LLM_READ_TIMEOUT:45s}
    max-attempts: ${NEXTVISIT_LLM_MAX_ATTEMPTS:3}
    max-output-tokens: ${NEXTVISIT_LLM_MAX_OUTPUT_TOKENS:512}
```

- [ ] **Step 6: Run the focused tests**

Run:

```bash
cd backend
env JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home ./gradlew :api:test --tests nextvisit.api.llm.LlmConfigurationTest --tests nextvisit.api.questions.QuestionServiceTest
```

Expected: settings tests pass and the disabled-default question tests still use templates without creating a worker.

- [ ] **Step 7: Commit configuration and executor wiring**

```bash
git add backend/api/src/main/java/nextvisit/api/llm/LlmProperties.java backend/api/src/main/java/nextvisit/api/llm/LlmConfiguration.java backend/api/src/main/resources/application.yml backend/api/src/test/java/nextvisit/api/llm/LlmConfigurationTest.java
git commit -m "feat: LLM 설정과 bounded worker 구성"
```

### Task 4: Build the minimal question-rewrite prompt

**Files:**
- Create: `backend/api/src/main/java/nextvisit/api/llm/QuestionRewritePrompt.java`
- Create: `backend/api/src/test/java/nextvisit/api/llm/QuestionRewritePromptTest.java`

**Interfaces:**
- Consumes: `QuestionCacheBody.Q` records produced by the deterministic engine bridge.
- Produces: `QuestionRewritePrompt.Prompt(String systemMessage, String userMessage)` and `Prompt build(List<QuestionCacheBody.Q>, Optional<String>)` for Tasks 6 and 7.

- [ ] **Step 1: Write tests that prove only rank and template text cross the boundary**

Create `QuestionRewritePromptTest.java`:

```java
package nextvisit.api.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import nextvisit.api.questions.QuestionCacheBody;
import org.junit.jupiter.api.Test;

class QuestionRewritePromptTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final QuestionRewritePrompt promptBuilder = new QuestionRewritePrompt(mapper);

    @Test
    void sendsOnlyRankAndTemplateSentence() throws Exception {
        QuestionCacheBody.Q input = new QuestionCacheBody.Q(
            1, "FREE_NOTE_SENTINEL", List.of("SECRET_ITEM"),
            new QuestionCacheBody.SignalRef("SECRET_ACTION", "SECRET_KIND"),
            "걷기는 3주째 그대로입니다. 어떻게 보시나요?",
            "OUTPUT_SENTINEL", QuestionCacheBody.SOURCE_TEMPLATE);

        QuestionRewritePrompt.Prompt prompt = promptBuilder.build(List.of(input), Optional.empty());
        JsonNode root = mapper.readTree(prompt.userMessage());
        JsonNode question = root.get("questions").get(0);
        Set<String> fields = StreamSupport.stream(
            ((Iterable<String>) () -> question.fieldNames()).spliterator(), false)
            .collect(Collectors.toSet());

        assertThat(fields).containsExactlyInAnyOrder("rank", "templateSentence");
        assertThat(question.get("rank").asInt()).isEqualTo(1);
        assertThat(question.get("templateSentence").asText())
            .isEqualTo("걷기는 3주째 그대로입니다. 어떻게 보시나요?");
        assertThat(prompt.userMessage()).doesNotContain(
            "FREE_NOTE_SENTINEL", "SECRET_ITEM", "SECRET_ACTION", "SECRET_KIND", "OUTPUT_SENTINEL");
        assertThat(prompt.systemMessage()).contains("/no_think");
    }

    @Test
    void retryAddsOnlyTheRuleNameAndKeepsTheSameUserJson() {
        QuestionCacheBody.Q input = new QuestionCacheBody.Q(
            1, "TYPE", List.of(), null, "3주째 그대로인가요?",
            "3주째 그대로인가요?", QuestionCacheBody.SOURCE_TEMPLATE);

        QuestionRewritePrompt.Prompt first = promptBuilder.build(List.of(input), Optional.empty());
        QuestionRewritePrompt.Prompt retry = promptBuilder.build(
            List.of(input), Optional.of("NUMBER_TOKENS"));

        assertThat(retry.userMessage()).isEqualTo(first.userMessage());
        assertThat(retry.systemMessage()).contains("NUMBER_TOKENS");
        assertThat(retry.systemMessage()).doesNotContain("거부된 문장");
    }
}
```

- [ ] **Step 2: Run the prompt tests and observe the compile failure**

Run:

```bash
cd backend
env JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home ./gradlew :api:test --tests nextvisit.api.llm.QuestionRewritePromptTest
```

Expected: test compilation fails because `QuestionRewritePrompt` does not exist.

- [ ] **Step 3: Implement the prompt as a serialization-only component**

Create `QuestionRewritePrompt.java`:

```java
package nextvisit.api.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import nextvisit.api.questions.QuestionCacheBody;
import org.springframework.stereotype.Component;

@Component
public class QuestionRewritePrompt {

    private static final String SYSTEM = """
        /no_think
        당신은 이미 결정된 보호자 질문의 뜻을 바꾸지 않고 자연스러운 한국어 질문으로만 다듬습니다.
        새 사실, 판단, 진단, 처방, 치료, 재활, 운동, 위험 평가 또는 행동 지시를 추가하지 마세요.
        항목, 기간, 방향과 모든 아라비아 숫자를 그대로 보존하세요.
        입력과 같은 rank를 정확히 한 번씩 반환하고 다른 rank를 만들지 마세요.
        각 sentence는 1자 이상 200자 이하이며 물음표 하나로 끝나야 합니다.
        마크다운과 설명을 쓰지 말고 정확히 {"questions":[{"rank":1,"sentence":"질문?"}]} 형태의 JSON 객체만 반환하세요.
        """;

    private final ObjectMapper mapper;

    public QuestionRewritePrompt(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Prompt build(List<QuestionCacheBody.Q> questions, Optional<String> retryRule) {
        List<InputQuestion> inputs = questions.stream()
            .map(q -> new InputQuestion(q.rank(), q.templateSentence()))
            .toList();
        String userMessage;
        try {
            userMessage = mapper.writeValueAsString(new InputPayload(inputs));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("question prompt serialization failed", e);
        }
        String systemMessage = retryRule
            .map(rule -> SYSTEM + "\n직전 응답은 검증 규칙 " + rule + "을 위반했습니다. 이 규칙을 지켜 다시 생성하세요.")
            .orElse(SYSTEM);
        return new Prompt(systemMessage, userMessage);
    }

    public record Prompt(String systemMessage, String userMessage) {}

    private record InputPayload(List<InputQuestion> questions) {}

    private record InputQuestion(int rank, String templateSentence) {}
}
```

- [ ] **Step 4: Run the prompt tests**

Run:

```bash
cd backend
env JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home ./gradlew :api:test --tests nextvisit.api.llm.QuestionRewritePromptTest
```

Expected: both tests pass and the user JSON contains no fields besides `rank` and `templateSentence`.

- [ ] **Step 5: Commit the prompt boundary**

```bash
git add backend/api/src/main/java/nextvisit/api/llm/QuestionRewritePrompt.java backend/api/src/test/java/nextvisit/api/llm/QuestionRewritePromptTest.java
git commit -m "feat: 최소 정보 LLM 질문 프롬프트 추가"
```

### Task 5: Reject unsafe or structurally altered LLM batches

**Files:**
- Create: `backend/api/src/main/java/nextvisit/api/llm/QuestionOutputGuard.java`
- Create: `backend/api/src/test/java/nextvisit/api/llm/QuestionOutputGuardTest.java`

**Interfaces:**
- Consumes: ordered `List<QuestionCacheBody.Q>`, raw assistant `content`, and `Templates.containsForbiddenWord(String)` from Task 1.
- Produces: `QuestionOutputGuard.Accepted(List<Rewrite>)`, `Rewrite(int rank, String sentence)`, `Rule`, and content-free `Rejected#rule()` for Task 7.

- [ ] **Step 1: Write a table-driven rejection suite and a reordered-success test**

Create `QuestionOutputGuardTest.java`:

```java
package nextvisit.api.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.util.List;
import java.util.stream.Stream;
import nextvisit.api.questions.QuestionCacheBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class QuestionOutputGuardTest {

    private final QuestionOutputGuard guard = new QuestionOutputGuard(new ObjectMapper());

    @Test
    void acceptsExactRanksAndRestoresInputOrder() {
        List<QuestionCacheBody.Q> input = List.of(
            question(1, "걷기를 3주 중 2주 지켜봤습니다. 어떻게 보시나요?"),
            question(2, "식사는 4주째 그대로입니다. 어떻게 보시나요?"));
        String content = """
            {"questions":[
              {"rank":2,"sentence":" 식사는 4주째 그대로인데 어떻게 보시나요? "},
              {"rank":1,"sentence":"걷기를 3주 중 2주 지켜봤는데 어떻게 보시나요?"}
            ]}
            """;

        QuestionOutputGuard.Accepted accepted = guard.validate(input, content);

        assertThat(accepted.rewrites()).containsExactly(
            new QuestionOutputGuard.Rewrite(1, "걷기를 3주 중 2주 지켜봤는데 어떻게 보시나요?"),
            new QuestionOutputGuard.Rewrite(2, "식사는 4주째 그대로인데 어떻게 보시나요?"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidResponses")
    void rejectsTheWholeBatch(String name, String content, QuestionOutputGuard.Rule rule) {
        List<QuestionCacheBody.Q> input = List.of(
            question(1, "걷기를 3주 중 2주 지켜봤습니다. 어떻게 보시나요?"),
            question(2, "식사는 4주째 그대로입니다. 어떻게 보시나요?"));

        QuestionOutputGuard.Rejected rejected = assertThrows(
            QuestionOutputGuard.Rejected.class, () -> guard.validate(input, content));

        assertThat(rejected.rule()).isEqualTo(rule);
        assertThat(rejected.getMessage()).isEqualTo(rule.name());
    }

    static Stream<Arguments> invalidResponses() {
        String longSentence = "가".repeat(201) + "?";
        String nfdForbidden = Normalizer.normalize("재활", Normalizer.Form.NFD);
        return Stream.of(
            Arguments.of("trailing json", "{\"questions\":[]} {\"extra\":1}",
                QuestionOutputGuard.Rule.JSON_OBJECT),
            Arguments.of("duplicate root field", "{\"questions\":[],\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.JSON_OBJECT),
            Arguments.of("duplicate question field", "{\"questions\":[{\"rank\":9,\"rank\":1,\"sentence\":\"3주 중 2주인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.JSON_OBJECT),
            Arguments.of("extra root field", "{\"questions\":[],\"extra\":1}",
                QuestionOutputGuard.Rule.ROOT_FIELDS),
            Arguments.of("wrong count", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주인가요?\"}]}",
                QuestionOutputGuard.Rule.QUESTION_COUNT),
            Arguments.of("extra question field", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주인가요?\",\"why\":\"x\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.QUESTION_FIELDS),
            Arguments.of("rank overflows int", "{\"questions\":[{\"rank\":4294967297,\"sentence\":\"3주 중 2주인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.QUESTION_FIELDS),
            Arguments.of("duplicate rank", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주인가요?\"},{\"rank\":1,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.RANK_SET),
            Arguments.of("multiple question marks", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주인가요??\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.QUESTION_MARK),
            Arguments.of("too long", "{\"questions\":[{\"rank\":1,\"sentence\":\"" + longSentence + "\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.SENTENCE_LENGTH),
            Arguments.of("markdown", "{\"questions\":[{\"rank\":1,\"sentence\":\"**3주** 중 2주인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.MARKDOWN),
            Arguments.of("lone backtick", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주`인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.MARKDOWN),
            Arguments.of("single underscore emphasis", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주 _그대로_인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.MARKDOWN),
            Arguments.of("fenced code", "{\"questions\":[{\"rank\":1,\"sentence\":\"```3주 중 2주```인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.MARKDOWN),
            Arguments.of("list prefix", "{\"questions\":[{\"rank\":1,\"sentence\":\"- 3주 중 2주인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.MARKDOWN),
            Arguments.of("blockquote", "{\"questions\":[{\"rank\":1,\"sentence\":\"> 3주 중 2주인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.MARKDOWN),
            Arguments.of("nfd forbidden word", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주 " + nfdForbidden + "인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.FORBIDDEN_WORD),
            Arguments.of("directive", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주 확인하세요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.DIRECTIVE),
            Arguments.of("changed numbers", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 1주인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.NUMBER_TOKENS),
            Arguments.of("omitted number", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 동안인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.NUMBER_TOKENS),
            Arguments.of("added number", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주와 5일인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.NUMBER_TOKENS),
            Arguments.of("duplicated number", "{\"questions\":[{\"rank\":1,\"sentence\":\"3주 중 2주, 다시 2주인가요?\"},{\"rank\":2,\"sentence\":\"4주째인가요?\"}]}",
                QuestionOutputGuard.Rule.NUMBER_TOKENS));
    }

    private static QuestionCacheBody.Q question(int rank, String template) {
        return new QuestionCacheBody.Q(rank, "TYPE", List.of(), null,
            template, template, QuestionCacheBody.SOURCE_TEMPLATE);
    }
}
```

- [ ] **Step 2: Run the guard test and observe the compile failure**

Run:

```bash
cd backend
env JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home ./gradlew :api:test --tests nextvisit.api.llm.QuestionOutputGuardTest
```

Expected: test compilation fails because `QuestionOutputGuard` does not exist.

- [ ] **Step 3: Implement strict parsing, language rules, and numeric multisets**

Create `QuestionOutputGuard.java`:

```java
package nextvisit.api.llm;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nextvisit.api.questions.QuestionCacheBody;
import nextvisit.engine.Templates;
import org.springframework.stereotype.Component;

@Component
public class QuestionOutputGuard {

    private static final Set<String> ROOT_FIELDS = Set.of("questions");
    private static final Set<String> QUESTION_FIELDS = Set.of("rank", "sentence");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern MARKDOWN = Pattern.compile(
        "(?m)[`*_~\\[\\]]|^\\s*(?:#{1,6}\\s|[-+]\\s|>\\s|\\d+\\.\\s)");
    private static final Pattern DIRECTIVE = Pattern.compile(
        "(?:하세요|하십시오|해 ?주세요|가세요|받으세요|드세요|복용하세요|해야 합니다|해야 해요)");

    private final ObjectMapper mapper;

    public QuestionOutputGuard(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Accepted validate(List<QuestionCacheBody.Q> templates, String content) {
        JsonNode root = parseOneObject(content);
        if (!fields(root).equals(ROOT_FIELDS)) {
            throw new Rejected(Rule.ROOT_FIELDS);
        }
        JsonNode questions = root.get("questions");
        if (questions == null || !questions.isArray()) {
            throw new Rejected(Rule.QUESTIONS_ARRAY);
        }
        if (questions.size() != templates.size()) {
            throw new Rejected(Rule.QUESTION_COUNT);
        }

        Map<Integer, QuestionCacheBody.Q> inputs = new HashMap<>();
        for (QuestionCacheBody.Q template : templates) {
            inputs.put(template.rank(), template);
        }
        Map<Integer, String> acceptedByRank = new HashMap<>();
        for (JsonNode candidate : questions) {
            if (!candidate.isObject() || !fields(candidate).equals(QUESTION_FIELDS)
                || !candidate.get("rank").isIntegralNumber()
                || !candidate.get("rank").canConvertToInt()
                || !candidate.get("sentence").isTextual()) {
                throw new Rejected(Rule.QUESTION_FIELDS);
            }
            int rank = candidate.get("rank").intValue();
            QuestionCacheBody.Q template = inputs.get(rank);
            if (template == null || acceptedByRank.containsKey(rank)) {
                throw new Rejected(Rule.RANK_SET);
            }
            String sentence = Normalizer.normalize(
                candidate.get("sentence").textValue().trim(), Normalizer.Form.NFC);
            int length = sentence.codePointCount(0, sentence.length());
            if (length < 1 || length > 200) {
                throw new Rejected(Rule.SENTENCE_LENGTH);
            }
            if (!Templates.isQuestion(sentence)
                || sentence.chars().filter(character -> character == '?').count() != 1) {
                throw new Rejected(Rule.QUESTION_MARK);
            }
            if (MARKDOWN.matcher(sentence).find()) {
                throw new Rejected(Rule.MARKDOWN);
            }
            if (Templates.containsForbiddenWord(sentence)) {
                throw new Rejected(Rule.FORBIDDEN_WORD);
            }
            if (DIRECTIVE.matcher(sentence).find()) {
                throw new Rejected(Rule.DIRECTIVE);
            }
            if (!numberTokens(template.templateSentence()).equals(numberTokens(sentence))) {
                throw new Rejected(Rule.NUMBER_TOKENS);
            }
            acceptedByRank.put(rank, sentence);
        }
        if (!acceptedByRank.keySet().equals(inputs.keySet())) {
            throw new Rejected(Rule.RANK_SET);
        }

        List<Rewrite> ordered = new ArrayList<>();
        for (QuestionCacheBody.Q template : templates) {
            ordered.add(new Rewrite(template.rank(), acceptedByRank.get(template.rank())));
        }
        return new Accepted(List.copyOf(ordered));
    }

    private JsonNode parseOneObject(String content) {
        if (content == null || content.isBlank()) {
            throw new Rejected(Rule.JSON_OBJECT);
        }
        try (JsonParser parser = mapper.createParser(content)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)) {
            JsonNode root = mapper.readTree(parser);
            if (root == null || !root.isObject() || parser.nextToken() != null) {
                throw new Rejected(Rule.JSON_OBJECT);
            }
            return root;
        } catch (IOException e) {
            throw new Rejected(Rule.JSON_OBJECT);
        }
    }

    private static Set<String> fields(JsonNode node) {
        Set<String> fields = new HashSet<>();
        node.fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private static Map<String, Integer> numberTokens(String sentence) {
        Map<String, Integer> tokens = new HashMap<>();
        Matcher matcher = NUMBER.matcher(sentence);
        while (matcher.find()) {
            tokens.merge(matcher.group(), 1, Integer::sum);
        }
        return tokens;
    }

    public enum Rule {
        JSON_OBJECT,
        ROOT_FIELDS,
        QUESTIONS_ARRAY,
        QUESTION_COUNT,
        QUESTION_FIELDS,
        RANK_SET,
        SENTENCE_LENGTH,
        QUESTION_MARK,
        MARKDOWN,
        FORBIDDEN_WORD,
        DIRECTIVE,
        NUMBER_TOKENS
    }

    public record Rewrite(int rank, String sentence) {}

    public record Accepted(List<Rewrite> rewrites) {}

    public static final class Rejected extends RuntimeException {
        private final Rule rule;

        public Rejected(Rule rule) {
            super(rule.name());
            this.rule = rule;
        }

        public Rule rule() {
            return rule;
        }
    }
}
```

The exception deliberately carries only the enum name; never attach `content` as a message or cause detail.

- [ ] **Step 4: Run guard and engine safety tests together**

Run:

```bash
cd backend
env JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home ./gradlew :engine:test --tests nextvisit.engine.TemplatesTest :api:test --tests nextvisit.api.llm.QuestionOutputGuardTest
```

Expected: all acceptance/rejection cases pass, including NFC normalization, numeric multiplicity, field exactness, and input-rank ordering.

- [ ] **Step 5: Commit the output trust boundary**

```bash
git add backend/api/src/main/java/nextvisit/api/llm/QuestionOutputGuard.java backend/api/src/test/java/nextvisit/api/llm/QuestionOutputGuardTest.java
git commit -m "feat: LLM 질문 출력 전체 검증 추가"
```

### Task 6: Implement the OpenAI-compatible provider adapter

**Files:**
- Create: `backend/api/src/main/java/nextvisit/api/llm/LlmClient.java`
- Create: `backend/api/src/main/java/nextvisit/api/llm/LlmFailureCode.java`
- Create: `backend/api/src/main/java/nextvisit/api/llm/LlmClientException.java`
- Create: `backend/api/src/main/java/nextvisit/api/llm/OpenAiCompatibleLlmClient.java`
- Modify: `backend/api/src/main/java/nextvisit/api/llm/LlmConfiguration.java`
- Create: `backend/api/src/test/java/nextvisit/api/llm/OpenAiCompatibleLlmClientTest.java`
- Modify: `backend/api/src/test/java/nextvisit/api/llm/LlmConfigurationTest.java`

**Interfaces:**
- Consumes: `QuestionRewritePrompt.Prompt` and all HTTP-related `LlmProperties` fields.
- Produces: `LlmClient#complete(QuestionRewritePrompt.Prompt)` returning raw assistant `content`; `LlmClientException#code()` with `HTTP_ERROR`, `TIMEOUT`, `CONNECTION_ERROR`, `INVALID_RESPONSE`, or `EMPTY_CONTENT`; conditional `LlmClient` and `RestClient` beans for Task 7.

- [ ] **Step 1: Write request-contract, optional-header, and envelope-failure tests**

Create `OpenAiCompatibleLlmClientTest.java`:

```java
package nextvisit.api.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleLlmClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiCompatibleLlmClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenAiCompatibleLlmClient(properties("access-id", "access-secret"),
            mapper, builder.build());
    }

    @Test
    void postsThePinnedContractAndReturnsOnlyAssistantContent() throws Exception {
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
            .andExpect(method(POST))
            .andExpect(request -> {
                assertThat(request.getHeaders().getFirst("Authorization")).isEqualTo("Bearer test-key");
                assertThat(request.getHeaders().getFirst("CF-Access-Client-Id")).isEqualTo("access-id");
                assertThat(request.getHeaders().getFirst("CF-Access-Client-Secret")).isEqualTo("access-secret");
                JsonNode body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                assertThat(body.get("model").asText()).isEqualTo("qwen3:4b-q8_0");
                assertThat(body.get("stream").asBoolean()).isFalse();
                assertThat(body.get("temperature").asDouble()).isEqualTo(0.1);
                assertThat(body.get("seed").asInt()).isZero();
                assertThat(body.get("max_tokens").asInt()).isEqualTo(512);
                assertThat(body.at("/response_format/type").asText()).isEqualTo("json_object");
                assertThat(body.at("/messages/0/role").asText()).isEqualTo("system");
                assertThat(body.at("/messages/1/role").asText()).isEqualTo("user");
            })
            .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"{\\\"questions\\\":[]}\"}}]}",
                MediaType.APPLICATION_JSON));

        String content = client.complete(new QuestionRewritePrompt.Prompt("system /no_think", "{\"questions\":[]}"));

        assertThat(content).isEqualTo("{\"questions\":[]}");
        server.verify();
    }

    @Test
    void sendsNoCloudflareHeaderUnlessBothValuesExist() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenAiCompatibleLlmClient(properties("access-id", ""), mapper, builder.build());
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
            .andExpect(request -> {
                assertThat(request.getHeaders()).doesNotContainKey("CF-Access-Client-Id");
                assertThat(request.getHeaders()).doesNotContainKey("CF-Access-Client-Secret");
            })
            .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"{\\\"questions\\\":[]}\"}}]}",
                MediaType.APPLICATION_JSON));

        client.complete(new QuestionRewritePrompt.Prompt("system", "{\"questions\":[]}"));
        server.verify();
    }

    @Test
    void mapsHttpAndMalformedEnvelopeWithoutLeakingTheBody() {
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("RESPONSE_SENTINEL"));
        LlmClientException http = assertThrows(LlmClientException.class,
            () -> client.complete(new QuestionRewritePrompt.Prompt("system", "user")));
        assertThat(http.code()).isEqualTo(LlmFailureCode.HTTP_ERROR);
        assertThat(http.getMessage()).doesNotContain("RESPONSE_SENTINEL");

        RestClient.Builder secondBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(secondBuilder).build();
        client = new OpenAiCompatibleLlmClient(properties("", ""), mapper, secondBuilder.build());
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
            .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));
        LlmClientException empty = assertThrows(LlmClientException.class,
            () -> client.complete(new QuestionRewritePrompt.Prompt("system", "user")));
        assertThat(empty.code()).isEqualTo(LlmFailureCode.EMPTY_CONTENT);
    }

    @Test
    void mapsInvalidJsonAndTransportTimeoutToStableCodes() {
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
            .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));
        LlmClientException invalid = assertThrows(LlmClientException.class,
            () -> client.complete(new QuestionRewritePrompt.Prompt("system", "user")));
        assertThat(invalid.code()).isEqualTo(LlmFailureCode.INVALID_RESPONSE);

        RestClient.Builder timeoutBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(timeoutBuilder).build();
        client = new OpenAiCompatibleLlmClient(properties("", ""), mapper,
            timeoutBuilder.build());
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
            .andRespond(request -> {
                throw new org.springframework.web.client.ResourceAccessException(
                    "request timed out", new java.net.http.HttpTimeoutException("timed out"));
            });
        LlmClientException timeout = assertThrows(LlmClientException.class,
            () -> client.complete(new QuestionRewritePrompt.Prompt("system", "user")));
        assertThat(timeout.code()).isEqualTo(LlmFailureCode.TIMEOUT);
    }

    private static LlmProperties properties(String accessId, String accessSecret) {
        return new LlmProperties(true, URI.create("http://localhost:11434/v1"),
            "qwen3:4b-q8_0", "test-key", accessId, accessSecret,
            Duration.ofSeconds(3), Duration.ofSeconds(45), 3, 512);
    }
}
```

- [ ] **Step 2: Run the adapter test and observe the compile failure**

Run:

```bash
cd backend
env JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home ./gradlew :api:test --tests nextvisit.api.llm.OpenAiCompatibleLlmClientTest
```

Expected: test compilation fails because the provider port, exception types, and adapter do not exist.

- [ ] **Step 3: Add provider-neutral port and content-free failures**

Create the three small contract files:

```java
// LlmClient.java
package nextvisit.api.llm;

public interface LlmClient {
    String complete(QuestionRewritePrompt.Prompt prompt);
}
```

```java
// LlmFailureCode.java
package nextvisit.api.llm;

public enum LlmFailureCode {
    HTTP_ERROR,
    TIMEOUT,
    CONNECTION_ERROR,
    INVALID_RESPONSE,
    EMPTY_CONTENT
}
```

```java
// LlmClientException.java
package nextvisit.api.llm;

public class LlmClientException extends RuntimeException {
    private final LlmFailureCode code;

    public LlmClientException(LlmFailureCode code) {
        super(code.name());
        this.code = code;
    }

    public LlmClientException(LlmFailureCode code, Throwable cause) {
        super(code.name(), cause);
        this.code = code;
    }

    public LlmFailureCode code() {
        return code;
    }
}
```

- [ ] **Step 4: Implement the OpenAI-compatible adapter**

Create `OpenAiCompatibleLlmClient.java`:

```java
package nextvisit.api.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public class OpenAiCompatibleLlmClient implements LlmClient {

    private final LlmProperties properties;
    private final ObjectMapper mapper;
    private final RestClient restClient;
    private final URI endpoint;

    public OpenAiCompatibleLlmClient(LlmProperties properties, ObjectMapper mapper,
                                     RestClient restClient) {
        this.properties = properties;
        this.mapper = mapper;
        this.restClient = restClient;
        this.endpoint = URI.create(stripTrailingSlash(properties.baseUrl().toString())
            + "/chat/completions");
    }

    @Override
    public String complete(QuestionRewritePrompt.Prompt prompt) {
        Request body = new Request(properties.model(), false, 0.1, 0,
            properties.maxOutputTokens(), new ResponseFormat("json_object"),
            List.of(new Message("system", prompt.systemMessage()),
                new Message("user", prompt.userMessage())));
        try {
            RestClient.RequestBodySpec request = restClient.post().uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);
            if (StringUtils.hasText(properties.apiKey())) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
            }
            if (StringUtils.hasText(properties.cfAccessClientId())
                && StringUtils.hasText(properties.cfAccessClientSecret())) {
                request.header("CF-Access-Client-Id", properties.cfAccessClientId());
                request.header("CF-Access-Client-Secret", properties.cfAccessClientSecret());
            }
            String envelope = request.body(body).retrieve().body(String.class);
            return assistantContent(envelope);
        } catch (RestClientResponseException e) {
            throw new LlmClientException(LlmFailureCode.HTTP_ERROR);
        } catch (ResourceAccessException e) {
            LlmFailureCode code = causedByTimeout(e)
                ? LlmFailureCode.TIMEOUT : LlmFailureCode.CONNECTION_ERROR;
            throw new LlmClientException(code, e);
        } catch (RestClientException e) {
            throw new LlmClientException(LlmFailureCode.CONNECTION_ERROR, e);
        }
    }

    private String assistantContent(String envelope) {
        if (envelope == null || envelope.isBlank()) {
            throw new LlmClientException(LlmFailureCode.EMPTY_CONTENT);
        }
        try {
            JsonNode root = mapper.readTree(envelope);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw new LlmClientException(LlmFailureCode.EMPTY_CONTENT);
            }
            JsonNode content = choices.get(0).path("message").path("content");
            if (!content.isTextual() || content.textValue().isBlank()) {
                throw new LlmClientException(LlmFailureCode.EMPTY_CONTENT);
            }
            return content.textValue();
        } catch (JsonProcessingException e) {
            throw new LlmClientException(LlmFailureCode.INVALID_RESPONSE);
        }
    }

    private static boolean causedByTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof HttpTimeoutException || current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private record Request(
        String model,
        boolean stream,
        double temperature,
        int seed,
        @JsonProperty("max_tokens") int maxTokens,
        @JsonProperty("response_format") ResponseFormat responseFormat,
        List<Message> messages
    ) {}

    private record ResponseFormat(String type) {}

    private record Message(String role, String content) {}
}
```

The HTTP error path intentionally discards provider response text. The exception cause is retained only for connection diagnostics and is never logged by later LLM components.

- [ ] **Step 5: Wire timeouts and the conditional client**

Add these imports and beans to `LlmConfiguration`:

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Bean
@ConditionalOnProperty(name = "nextvisit.llm.enabled", havingValue = "true")
RestClient llmRestClient(LlmProperties properties) {
    HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout())
        .build();
    JdkClientHttpRequestFactory requestFactory =
        new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());
    return RestClient.builder().requestFactory(requestFactory).build();
}

@Bean
@ConditionalOnProperty(name = "nextvisit.llm.enabled", havingValue = "true")
LlmClient llmClient(LlmProperties properties, ObjectMapper mapper,
                    RestClient llmRestClient) {
    return new OpenAiCompatibleLlmClient(properties, mapper, llmRestClient);
}
```

Update the `ApplicationContextRunner` in `LlmConfigurationTest` so its isolated enabled context supplies Jackson, then assert the client is present only when enabled:

```java
import com.fasterxml.jackson.databind.ObjectMapper;

private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
    .withBean(ObjectMapper.class, ObjectMapper::new)
    .withUserConfiguration(LlmConfiguration.class);
```

Add `assertThat(context).hasSingleBean(LlmClient.class);` to `enabledCreatesOneBoundedWorker()` and `assertThat(context).doesNotHaveBean(LlmClient.class);` to `defaultsAreSafeAndDoNotCreateAWorker()`.

- [ ] **Step 6: Run adapter and configuration tests**

Run:

```bash
cd backend
env JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home ./gradlew :api:test --tests nextvisit.api.llm.OpenAiCompatibleLlmClientTest --tests nextvisit.api.llm.LlmConfigurationTest
```

Expected: request JSON and headers match the approved contract, malformed or failed responses expose only an enum code, and disabled configuration has no provider bean.

- [ ] **Step 7: Commit the provider adapter**

```bash
git add backend/api/src/main/java/nextvisit/api/llm backend/api/src/test/java/nextvisit/api/llm
git commit -m "feat: OpenAI 호환 LLM client 추가"
```

### Task 7: Coordinate retries and atomically write all-or-none results

**Files:**
- Create: `backend/api/src/main/java/nextvisit/api/llm/QuestionGenerationRequested.java`
- Create: `backend/api/src/main/java/nextvisit/api/llm/QuestionGenerationResultWriter.java`
- Create: `backend/api/src/main/java/nextvisit/api/llm/QuestionGenerationCoordinator.java`
- Create: `backend/api/src/test/java/nextvisit/api/llm/QuestionGenerationCoordinatorTest.java`

**Interfaces:**
- Consumes: `LlmClient`, `QuestionRewritePrompt`, `QuestionOutputGuard`, `LlmProperties.maxAttempts()`, `QuestionCacheRepository`, `QuestionCacheBody`, and Task 2 compare-and-set queries.
- Produces: `QuestionGenerationRequested(UUID caseId, UUID generationId)`; `QuestionGenerationResultWriter#markDone(UUID, UUID, QuestionCacheBody)` and `markFailed(UUID, UUID)`; `QuestionGenerationCoordinator#generate(QuestionGenerationRequested)` for Task 8.

- [ ] **Step 1: Write retry, whole-batch fallback, stale, and log-redaction tests**

Create `QuestionGenerationCoordinatorTest.java`:

```java
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
        when(client.complete(any()))
            .thenReturn(response("3주 중 2주인가요??", "4주째인가요?"))
            .thenReturn(response("3주 중 1주인가요?", "4주째인가요?"))
            .thenReturn(response("걷기를 3주 중 2주 보셨는데 어떻게 보시나요?",
                "식사가 4주째 같은데 어떻게 보시나요?"));

        coordinator.generate(new QuestionGenerationRequested(caseId, generationId));

        ArgumentCaptor<QuestionRewritePrompt.Prompt> prompts =
            ArgumentCaptor.forClass(QuestionRewritePrompt.Prompt.class);
        verify(client, org.mockito.Mockito.times(3)).complete(prompts.capture());
        assertThat(prompts.getAllValues().get(0).systemMessage()).doesNotContain("직전 응답");
        assertThat(prompts.getAllValues().get(1).systemMessage()).contains("QUESTION_MARK");
        assertThat(prompts.getAllValues().get(2).systemMessage()).contains("NUMBER_TOKENS");

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
```

- [ ] **Step 2: Run the coordinator test and observe the compile failure**

Run:

```bash
cd backend
env JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home ./gradlew :api:test --tests nextvisit.api.llm.QuestionGenerationCoordinatorTest
```

Expected: test compilation fails because the event, result writer, and coordinator do not exist.

- [ ] **Step 3: Add the immutable event identity**

Create `QuestionGenerationRequested.java`:

```java
package nextvisit.api.llm;

import java.util.UUID;

public record QuestionGenerationRequested(UUID caseId, UUID generationId) {}
```

- [ ] **Step 4: Add short compare-and-set result transactions**

Create `QuestionGenerationResultWriter.java`:

```java
package nextvisit.api.llm;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import nextvisit.api.common.Json;
import nextvisit.api.questions.QuestionCacheBody;
import nextvisit.api.questions.QuestionCacheRepository;
import nextvisit.api.questions.QuestionCacheStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class QuestionGenerationResultWriter {

    private final QuestionCacheRepository caches;
    private final Json json;
    private final Clock clock;

    public QuestionGenerationResultWriter(QuestionCacheRepository caches, Json json, Clock clock) {
        this.caches = caches;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markDone(UUID caseId, UUID generationId, QuestionCacheBody body) {
        return caches.completeGenerationIfPending(caseId, generationId,
            QuestionCacheStatus.LLM_PENDING, QuestionCacheStatus.LLM_DONE,
            json.toJson(body), Instant.now(clock)) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(UUID caseId, UUID generationId) {
        return caches.failGenerationIfPending(caseId, generationId,
            QuestionCacheStatus.LLM_PENDING, QuestionCacheStatus.LLM_FAILED,
            Instant.now(clock)) == 1;
    }
}
```

`markFailed` changes only state and timestamp; it deliberately leaves the already-safe template body untouched.

- [ ] **Step 5: Implement retry and whole-batch rewrite orchestration outside a transaction**

Create `QuestionGenerationCoordinator.java`:

```java
package nextvisit.api.llm;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import nextvisit.api.common.Json;
import nextvisit.api.questions.QuestionCache;
import nextvisit.api.questions.QuestionCacheBody;
import nextvisit.api.questions.QuestionCacheRepository;
import nextvisit.api.questions.QuestionCacheStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "nextvisit.llm.enabled", havingValue = "true")
public class QuestionGenerationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(QuestionGenerationCoordinator.class);

    private final QuestionCacheRepository caches;
    private final Json json;
    private final LlmClient client;
    private final QuestionRewritePrompt prompts;
    private final QuestionOutputGuard guard;
    private final QuestionGenerationResultWriter writer;
    private final LlmProperties properties;

    public QuestionGenerationCoordinator(QuestionCacheRepository caches, Json json, LlmClient client,
                                         QuestionRewritePrompt prompts, QuestionOutputGuard guard,
                                         QuestionGenerationResultWriter writer, LlmProperties properties) {
        this.caches = caches;
        this.json = json;
        this.client = client;
        this.prompts = prompts;
        this.guard = guard;
        this.writer = writer;
        this.properties = properties;
    }

    public void generate(QuestionGenerationRequested request) {
        long started = System.nanoTime();
        Optional<QuestionCache> found = caches.findByCaseId(request.caseId());
        if (found.isEmpty() || !request.generationId().equals(found.get().getGenerationId())
            || found.get().getStatus() != QuestionCacheStatus.LLM_PENDING) {
            log.debug("llm generation result generationId={} model={} attempts=0 elapsedMs={} code=STALE_RESULT",
                request.generationId(), properties.model(), elapsedMillis(started));
            return;
        }

        QuestionCacheBody templates;
        try {
            templates = json.fromJson(found.get().getBody(), QuestionCacheBody.class);
        } catch (RuntimeException e) {
            finishFailed(request, started, 0, "CACHE_BODY_INVALID");
            return;
        }

        String retryRule = null;
        String lastCode = "ATTEMPTS_EXHAUSTED";
        int attempts = 0;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            if (!isCurrent(request)) {
                log.debug("llm generation result generationId={} model={} attempts={} elapsedMs={} code=STALE_RESULT",
                    request.generationId(), properties.model(), attempts, elapsedMillis(started));
                return;
            }
            attempts = attempt;
            try {
                QuestionRewritePrompt.Prompt prompt = prompts.build(templates.questions(),
                    Optional.ofNullable(retryRule));
                String content = client.complete(prompt);
                QuestionOutputGuard.Accepted accepted = guard.validate(templates.questions(), content);
                QuestionCacheBody rewritten = rewriteAll(templates, accepted);
                if (writer.markDone(request.caseId(), request.generationId(), rewritten)) {
                    log.info("llm generation result generationId={} model={} attempts={} elapsedMs={} code=SUCCESS",
                        request.generationId(), properties.model(), attempts, elapsedMillis(started));
                } else {
                    log.debug("llm generation result generationId={} model={} attempts={} elapsedMs={} code=STALE_RESULT",
                        request.generationId(), properties.model(), attempts, elapsedMillis(started));
                }
                return;
            } catch (QuestionOutputGuard.Rejected e) {
                retryRule = e.rule().name();
                lastCode = retryRule;
            } catch (LlmClientException e) {
                retryRule = null;
                lastCode = e.code().name();
            } catch (RuntimeException e) {
                retryRule = null;
                lastCode = "INTERNAL_ERROR";
            }
        }
        finishFailed(request, started, attempts, lastCode);
    }

    private boolean isCurrent(QuestionGenerationRequested request) {
        return caches.existsByCaseIdAndGenerationIdAndStatus(
            request.caseId(), request.generationId(), QuestionCacheStatus.LLM_PENDING);
    }

    private void finishFailed(QuestionGenerationRequested request, long started,
                              int attempts, String code) {
        if (writer.markFailed(request.caseId(), request.generationId())) {
            log.warn("llm generation result generationId={} model={} attempts={} elapsedMs={} code={}",
                request.generationId(), properties.model(), attempts, elapsedMillis(started), code);
        } else {
            log.debug("llm generation result generationId={} model={} attempts={} elapsedMs={} code=STALE_RESULT",
                request.generationId(), properties.model(), attempts, elapsedMillis(started));
        }
    }

    private static QuestionCacheBody rewriteAll(QuestionCacheBody templates,
                                                QuestionOutputGuard.Accepted accepted) {
        Map<Integer, String> byRank = new HashMap<>();
        for (QuestionOutputGuard.Rewrite rewrite : accepted.rewrites()) {
            byRank.put(rewrite.rank(), rewrite.sentence());
        }
        List<QuestionCacheBody.Q> rewritten = templates.questions().stream()
            .map(question -> new QuestionCacheBody.Q(
                question.rank(), question.type(), question.items(), question.signal(),
                question.templateSentence(), byRank.get(question.rank()), QuestionCacheBody.SOURCE_LLM))
            .toList();
        return new QuestionCacheBody(rewritten, templates.engineDetectionCount());
    }

    private static long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
}
```

Do not add `@Transactional` to the coordinator. Each repository read is short-lived, the provider call runs without a database transaction, and only the result writer opens `REQUIRES_NEW`.

- [ ] **Step 6: Run the coordinator and persistence tests**

Run:

```bash
cd backend
env JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home ./gradlew :api:test --tests nextvisit.api.llm.QuestionGenerationCoordinatorTest --tests nextvisit.api.PersistenceTest
```

Expected: retry prompts carry only rule names, partial unsafe batches fully fail, content is absent from captured logs, and compare-and-set persistence rejects stale results.

- [ ] **Step 7: Commit orchestration and result transactions**

```bash
git add backend/api/src/main/java/nextvisit/api/llm backend/api/src/test/java/nextvisit/api/llm/QuestionGenerationCoordinatorTest.java
git commit -m "feat: LLM 질문 재시도와 원자적 폴백 구현"
```

### Task 8: Publish after commit and dispatch without blocking requests

**Files:**
- Create: `backend/api/src/main/java/nextvisit/api/llm/QuestionGenerationDispatcher.java`
- Modify: `backend/api/src/main/java/nextvisit/api/questions/QuestionService.java`
- Create: `backend/api/src/test/java/nextvisit/api/llm/QuestionGenerationDispatcherTest.java`
- Create: `backend/api/src/test/java/nextvisit/api/llm/QuestionAsyncIntegrationTest.java`
- Modify: `backend/api/src/test/java/nextvisit/api/questions/QuestionServiceTest.java`

**Interfaces:**
- Consumes: `QuestionGenerationRequested`, `QuestionGenerationCoordinator#generate`, `QuestionGenerationResultWriter#markFailed`, conditional `@Qualifier("llmTaskExecutor") Executor`, and typed cache state.
- Produces: `QuestionGenerationDispatcher#onRequested(QuestionGenerationRequested)` as an `AFTER_COMMIT` listener; `QuestionService.refresh(UUID)` storing `LLM_PENDING` only when enabled and non-empty, publishing exactly one event, and otherwise storing `READY`.

- [ ] **Step 1: Write a unit test for accepted work and queue rejection**

Create `QuestionGenerationDispatcherTest.java`:

```java
package nextvisit.api.llm;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class QuestionGenerationDispatcherTest {

    @Mock QuestionGenerationCoordinator coordinator;
    @Mock QuestionGenerationResultWriter writer;

    @Test
    void submittedRunnableOwnsTheSlowWork() {
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        Executor executor = submitted::set;
        QuestionGenerationDispatcher dispatcher =
            new QuestionGenerationDispatcher(executor, coordinator, writer);
        QuestionGenerationRequested event =
            new QuestionGenerationRequested(UUID.randomUUID(), UUID.randomUUID());

        dispatcher.onRequested(event);

        verify(coordinator, never()).generate(event);
        submitted.get().run();
        verify(coordinator).generate(event);
    }

    @Test
    void rejectedQueueMarksOnlyThatGenerationFailedAndDoesNotThrow() {
        Executor executor = command -> {
            throw new RejectedExecutionException("full");
        };
        QuestionGenerationDispatcher dispatcher =
            new QuestionGenerationDispatcher(executor, coordinator, writer);
        QuestionGenerationRequested event =
            new QuestionGenerationRequested(UUID.randomUUID(), UUID.randomUUID());
        when(writer.markFailed(event.caseId(), event.generationId())).thenReturn(true);

        dispatcher.onRequested(event);

        verify(writer).markFailed(event.caseId(), event.generationId());
        verify(coordinator, never()).generate(event);
    }
}
```

- [ ] **Step 2: Write enabled integration tests for pending visibility and transaction phase**

Create `QuestionAsyncIntegrationTest.java`. Use the existing onboarding and demo seed helpers so the test exercises real JPA, Flyway, engine output, prompt, guard, coordinator, and result writer while replacing only the network port:

```java
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "nextvisit.llm.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
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
    @MockitoBean LlmClient client;

    private final AtomicReference<CountDownLatch> release = new AtomicReference<>();

    @BeforeEach
    void resetClient() {
        reset(client);
        release.set(new CountDownLatch(0));
        clock.set(TestClockConfig.DEFAULT_TODAY);
    }

    @AfterEach
    void unblockWorker() {
        release.get().countDown();
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
        CountDownLatch firstCall = new CountDownLatch(1);
        release.set(new CountDownLatch(1));
        stubSuccessfulRewrite(firstCall);

        questions.refresh(caseId);
        assertThat(firstCall.await(2, TimeUnit.SECONDS)).isTrue();
        UUID olderGeneration = caches.findByCaseId(caseId).orElseThrow().getGenerationId();

        questions.refresh(caseId);
        UUID newerGeneration = caches.findByCaseId(caseId).orElseThrow().getGenerationId();
        assertThat(newerGeneration).isNotEqualTo(olderGeneration);

        release.get().countDown();
        awaitStatus(caseId, QuestionCacheStatus.LLM_DONE);
        assertThat(caches.findByCaseId(caseId).orElseThrow().getGenerationId())
            .isEqualTo(newerGeneration);
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
            QuestionRewritePrompt.Prompt prompt = invocation.getArgument(0);
            JsonNode input = mapper.readTree(prompt.userMessage()).get("questions");
            ObjectNode output = mapper.createObjectNode();
            ArrayNode rewritten = output.putArray("questions");
            for (JsonNode question : input) {
                rewritten.addObject()
                    .put("rank", question.get("rank").asInt())
                    .put("sentence", question.get("templateSentence").asText());
            }
            return mapper.writeValueAsString(output);
        });
    }

    private void awaitStatus(UUID caseId, QuestionCacheStatus expected) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (caches.findByCaseId(caseId).map(cache -> cache.getStatus() == expected).orElse(false)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("cache did not reach " + expected);
    }
}
```

- [ ] **Step 3: Run both new tests and observe the expected failures**

Run:

```bash
cd backend
env JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home ./gradlew :api:test --tests nextvisit.api.llm.QuestionGenerationDispatcherTest --tests nextvisit.api.llm.QuestionAsyncIntegrationTest
```

Expected: dispatcher test compilation fails because the listener does not exist; integration expectations also fail because refresh still writes `READY` and publishes no event.

- [ ] **Step 4: Implement the non-throwing after-commit dispatcher**

Create `QuestionGenerationDispatcher.java`:

```java
package nextvisit.api.llm;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@ConditionalOnProperty(name = "nextvisit.llm.enabled", havingValue = "true")
public class QuestionGenerationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(QuestionGenerationDispatcher.class);

    private final Executor executor;
    private final QuestionGenerationCoordinator coordinator;
    private final QuestionGenerationResultWriter writer;

    public QuestionGenerationDispatcher(@Qualifier("llmTaskExecutor") Executor executor,
                                        QuestionGenerationCoordinator coordinator,
                                        QuestionGenerationResultWriter writer) {
        this.executor = executor;
        this.coordinator = coordinator;
        this.writer = writer;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(QuestionGenerationRequested request) {
        try {
            executor.execute(() -> coordinator.generate(request));
        } catch (RejectedExecutionException e) {
            boolean markedFailed = false;
            try {
                markedFailed = writer.markFailed(request.caseId(), request.generationId());
            } catch (RuntimeException ignored) {
                // The cache still contains its safe template body. Never fail the committed user request.
            }
            log.warn("llm generation result generationId={} attempts=0 elapsedMs=0 code=QUEUE_REJECTED markedFailed={}",
                request.generationId(), markedFailed);
        }
    }
}
```

Do not enable `fallbackExecution`: an event outside a successful transaction must never call the LLM.

- [ ] **Step 5: Make refresh template-first and event-driven**

Add `ApplicationEventPublisher`, `LlmProperties`, and `QuestionGenerationRequested` dependencies to `QuestionService`. Preserve the deterministic engine/body construction, then use this exact state/upsert/publish block:

```java
boolean generateWithLlm = properties.enabled() && !body.questions().isEmpty();
QuestionCacheStatus status = generateWithLlm
    ? QuestionCacheStatus.LLM_PENDING : QuestionCacheStatus.READY;
UUID generationId = UUID.randomUUID();

Optional<QuestionCache> existing = caches.findByCaseId(caseId);
if (existing.isPresent()) {
    existing.get().update(week, status, generationId, js, now);
    caches.save(existing.get());
} else {
    caches.save(new QuestionCache(caseId, week, status, generationId, js, now));
}
if (generateWithLlm) {
    events.publishEvent(new QuestionGenerationRequested(caseId, generationId));
}
return body;
```

The constructor fields and parameters must be exactly:

```java
private final LlmProperties properties;
private final ApplicationEventPublisher events;

public QuestionService(CaseRepository cases, SnapshotRepository snapshots,
                       QuestionCacheRepository caches, EngineBridge bridge, Json json,
                       Clock clock, LlmProperties properties,
                       ApplicationEventPublisher events) {
    this.cases = cases;
    this.snapshots = snapshots;
    this.caches = caches;
    this.bridge = bridge;
    this.json = json;
    this.clock = clock;
    this.properties = properties;
    this.events = events;
}
```

- [ ] **Step 6: Preserve disabled-mode assertions**

In `QuestionServiceTest`, keep exact demo sentence assertions and add:

```java
assertEquals(QuestionCacheStatus.READY,
    caches.findByCaseId(caseId).orElseThrow().getStatus());
```

This test class uses `application-test.yml` plus the default `enabled=false`, so it is the explicit non-regression gate for existing users and frontend consumers.

- [ ] **Step 7: Run all API tests twice to expose asynchronous leakage**

Run:

```bash
cd backend
env JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home ./gradlew :api:test
env JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home ./gradlew :api:test
```

Expected: both runs pass; slow completion never blocks refresh, rollback starts no provider call, pending reads return templates, and existing API/demo byte strings remain unchanged with LLM disabled.

- [ ] **Step 8: Commit the asynchronous vertical slice**

```bash
git add backend/api/src/main/java/nextvisit/api/llm/QuestionGenerationDispatcher.java backend/api/src/main/java/nextvisit/api/questions/QuestionService.java backend/api/src/test/java/nextvisit/api/llm backend/api/src/test/java/nextvisit/api/questions/QuestionServiceTest.java
git commit -m "feat: 커밋 후 비동기 LLM 질문 생성 연결"
```

### Task 9: Build the pinned CPU Ollama stack and idempotent model tools

**Files:**
- Modify: `.gitignore`
- Create: `infra/llm/Dockerfile`
- Create: `infra/llm/compose.yml`
- Create: `infra/llm/.env.example`
- Create: `infra/llm/scripts/wait-for-ollama.sh`
- Create: `infra/llm/scripts/ensure-model.sh`
- Create: `infra/llm/scripts/smoke-openai.sh`
- Create: `infra/llm/tests/ensure-model-test.sh`

**Interfaces:**
- Consumes: Docker Engine, Compose v2, `curl`, `jq`, and an optional local `infra/llm/.env` containing only non-secret overrides.
- Produces: image `nextvisit-ollama:0.33.3`; services `ollama` and one-shot `model-init`; named volume `nextvisit-llm-ollama-data`; `ensure-model.sh`; and `smoke-openai.sh [base-url]` for Task 11 deployment.

- [ ] **Step 1: Write a fake-CLI test for idempotent model initialization**

Create `infra/llm/tests/ensure-model-test.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT
mkdir -p "$test_root/bin"
fake_log="$test_root/ollama.log"

cat >"$test_root/bin/ollama" <<'FAKE'
#!/bin/sh
set -eu
case "$1" in
  list)
    exit 0
    ;;
  show)
    if [ "${FAKE_MODEL_PRESENT:-false}" = "true" ]; then
      exit 0
    fi
    exit 1
    ;;
  pull)
    printf '%s\n' "$2" >>"$FAKE_OLLAMA_LOG"
    ;;
  *)
    exit 2
    ;;
esac
FAKE
chmod +x "$test_root/bin/ollama"

PATH="$test_root/bin:$script_dir/../scripts:$PATH" \
FAKE_OLLAMA_LOG="$fake_log" \
FAKE_MODEL_PRESENT=true \
OLLAMA_HOST=http://fake-ollama:11434 \
OLLAMA_MODEL=qwen3:4b-q8_0 \
OLLAMA_WAIT_ATTEMPTS=1 \
"$script_dir/../scripts/ensure-model.sh"
test ! -e "$fake_log"

PATH="$test_root/bin:$script_dir/../scripts:$PATH" \
FAKE_OLLAMA_LOG="$fake_log" \
FAKE_MODEL_PRESENT=false \
OLLAMA_HOST=http://fake-ollama:11434 \
OLLAMA_MODEL=qwen3:4b-q8_0 \
OLLAMA_WAIT_ATTEMPTS=1 \
"$script_dir/../scripts/ensure-model.sh"
test "$(cat "$fake_log")" = "qwen3:4b-q8_0"

printf '%s\n' "ensure-model tests passed"
```

The test creates only a temporary fake `ollama` executable; it never downloads a model.

- [ ] **Step 2: Run the shell test and observe the missing-script failure**

Run:

```bash
cd infra/llm/tests
bash ensure-model-test.sh
```

Expected: failure with `../scripts/ensure-model.sh: No such file or directory`.

- [ ] **Step 3: Add readiness and idempotent pull scripts**

Create `wait-for-ollama.sh`:

```sh
#!/bin/sh
set -eu

attempts="${OLLAMA_WAIT_ATTEMPTS:-60}"
interval="${OLLAMA_WAIT_INTERVAL_SECONDS:-2}"
attempt=1

while [ "$attempt" -le "$attempts" ]; do
  if ollama list >/dev/null 2>&1; then
    exit 0
  fi
  if [ "$attempt" -lt "$attempts" ]; then
    sleep "$interval"
  fi
  attempt=$((attempt + 1))
done

printf '%s\n' "Ollama did not become ready within ${attempts} attempts" >&2
exit 1
```

Create `ensure-model.sh`:

```sh
#!/bin/sh
set -eu

: "${OLLAMA_HOST:?OLLAMA_HOST is required}"
: "${OLLAMA_MODEL:?OLLAMA_MODEL is required}"

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
export OLLAMA_HOST
"$script_dir/wait-for-ollama.sh"
if ollama show "$OLLAMA_MODEL" >/dev/null 2>&1; then
  printf '%s\n' "Ollama model is already present: $OLLAMA_MODEL"
  exit 0
fi

printf '%s\n' "Pulling Ollama model: $OLLAMA_MODEL"
ollama pull "$OLLAMA_MODEL" >/dev/null
printf '%s\n' "Ollama model is ready: $OLLAMA_MODEL"
```

- [ ] **Step 4: Add a response-body-silent OpenAI smoke script**

Create `smoke-openai.sh`:

```sh
#!/bin/sh
set -eu

base_url="${1:-http://127.0.0.1:11434/v1}"
model="${OLLAMA_MODEL:-qwen3:4b-q8_0}"
payload_file="$(mktemp)"
response_file="$(mktemp)"
trap 'rm -f "$payload_file" "$response_file"' EXIT HUP INT TERM

jq -n --arg model "$model" '{
  model: $model,
  stream: false,
  temperature: 0.1,
  seed: 0,
  max_tokens: 128,
  response_format: {type: "json_object"},
  messages: [
    {role: "system", content: "/no_think JSON 객체만 반환하세요. 정확히 {\"questions\":[{\"rank\":1,\"sentence\":\"확인할까요?\"}]} 형식입니다."},
    {role: "user", content: "{\"questions\":[{\"rank\":1,\"templateSentence\":\"확인할까요?\"}]}"}
  ]
}' >"$payload_file"

status="$(curl --silent --show-error \
  --connect-timeout 5 --max-time 120 \
  --output "$response_file" --write-out '%{http_code}' \
  --header 'Content-Type: application/json' \
  --data-binary "@$payload_file" \
  "${base_url%/}/chat/completions")"

if [ "$status" != "200" ]; then
  printf '%s\n' "OpenAI smoke failed with HTTP $status" >&2
  exit 1
fi

if ! jq -e '
  (.choices | type == "array" and length > 0) and
  (.choices[0].message.content | type == "string") and
  ((.choices[0].message.content | fromjson) as $content |
    ($content | keys == ["questions"]) and
    ($content.questions | type == "array" and length == 1) and
    ($content.questions[0].rank == 1) and
    ($content.questions[0].sentence | type == "string" and endswith("?")))
' "$response_file" >/dev/null 2>&1; then
  printf '%s\n' "OpenAI smoke returned an invalid envelope" >&2
  exit 1
fi

printf '%s\n' "OpenAI-compatible smoke passed"
```

The failure path prints only an HTTP code or a fixed validation message; it never prints provider content or token counts.

- [ ] **Step 5: Mark scripts executable and rerun the fake test plus ShellCheck**

Run:

```bash
chmod +x infra/llm/scripts/wait-for-ollama.sh infra/llm/scripts/ensure-model.sh infra/llm/scripts/smoke-openai.sh infra/llm/tests/ensure-model-test.sh
cd infra/llm/tests
bash ensure-model-test.sh
cd ../../..
shellcheck infra/llm/scripts/*.sh infra/llm/tests/*.sh
```

Expected: fake model-present and model-missing branches pass; ShellCheck exits zero.

- [ ] **Step 6: Add the pinned image and CPU Compose file**

Create `Dockerfile`:

```dockerfile
FROM ollama/ollama:0.33.3@sha256:32931b46719f673c05fdbaa81ccb26da18ea4a1c57590a754874ab28ba269eb2

COPY --chmod=755 scripts/wait-for-ollama.sh /usr/local/bin/wait-for-ollama.sh
COPY --chmod=755 scripts/ensure-model.sh /usr/local/bin/ensure-model.sh
```

Create `compose.yml`:

```yaml
name: nextvisit-llm

services:
  ollama:
    build:
      context: .
      dockerfile: Dockerfile
    image: nextvisit-ollama:0.33.3
    environment:
      OLLAMA_HOST: 0.0.0.0:11434
      OLLAMA_CONTEXT_LENGTH: "${OLLAMA_CONTEXT_LENGTH:-2048}"
    ports:
      - "127.0.0.1:${OLLAMA_PORT:-11434}:11434"
    volumes:
      - ollama-data:/root/.ollama
    restart: unless-stopped
    healthcheck:
      test: ["CMD-SHELL", "OLLAMA_HOST=http://127.0.0.1:11434 ollama list >/dev/null 2>&1"]
      interval: 5s
      timeout: 3s
      retries: 24
      start_period: 10s

  model-init:
    image: nextvisit-ollama:0.33.3
    entrypoint: ["/bin/sh", "/usr/local/bin/ensure-model.sh"]
    environment:
      OLLAMA_HOST: http://ollama:11434
      OLLAMA_MODEL: "${OLLAMA_MODEL:-qwen3:4b-q8_0}"
      OLLAMA_WAIT_ATTEMPTS: "${OLLAMA_WAIT_ATTEMPTS:-60}"
    depends_on:
      ollama:
        condition: service_healthy
    restart: "no"

volumes:
  ollama-data:
    name: nextvisit-llm-ollama-data
```

Create `.env.example`:

```dotenv
OLLAMA_MODEL=qwen3:4b-q8_0
OLLAMA_CONTEXT_LENGTH=2048
OLLAMA_PORT=11434
OLLAMA_WAIT_ATTEMPTS=60
```

Add this exact line to the root `.gitignore`:

```gitignore
infra/llm/.env
```

- [ ] **Step 7: Render, build, and health-check the CPU server without pulling the model**

Run:

```bash
cd infra/llm
docker compose -f compose.yml config --quiet
docker build --tag nextvisit-ollama:0.33.3 .
docker compose -f compose.yml up --detach --wait --build ollama
curl --fail --silent --show-error http://127.0.0.1:11434/api/tags >/dev/null
docker compose -f compose.yml down
```

Expected: Compose renders, the pinned image builds, Ollama becomes healthy on loopback, and shutdown preserves `nextvisit-llm-ollama-data`. Do not add `--volumes`.

- [ ] **Step 8: Commit CPU container and scripts**

```bash
git add .gitignore infra/llm
git commit -m "feat: Ollama CPU 컨테이너와 모델 초기화 추가"
```

### Task 10: Add NVIDIA, Tunnel, and deferred Ubuntu operations

**Files:**
- Create: `infra/llm/compose.gpu.yml`
- Create: `infra/llm/compose.tunnel.yml`
- Create: `infra/llm/scripts/verify-host.sh`
- Create: `infra/llm/README.md`

**Interfaces:**
- Consumes: Task 9 services, named volume, `NEXTVISIT_LLM_ENV_FILE`, Docker's NVIDIA runtime, and a Cloudflare remotely managed tunnel whose service target is `http://ollama:11434`.
- Produces: `verify-host.sh cpu|gpu`; GPU device reservation; final production configuration with no `ollama.ports`; and the exact future enablement sequence for the private repository laptop.

- [ ] **Step 1: Write the read-only host verification script**

Create `verify-host.sh`:

```sh
#!/bin/sh
set -eu

mode="${1:-cpu}"
case "$mode" in
  cpu|gpu) ;;
  *)
    printf '%s\n' "Usage: $0 cpu|gpu" >&2
    exit 2
    ;;
esac

command -v docker >/dev/null 2>&1 || {
  printf '%s\n' "docker is required" >&2
  exit 1
}
command -v curl >/dev/null 2>&1 || {
  printf '%s\n' "curl is required" >&2
  exit 1
}
command -v jq >/dev/null 2>&1 || {
  printf '%s\n' "jq is required" >&2
  exit 1
}
docker info >/dev/null
compose_version="$(docker compose version --short | sed 's/^v//; s/-.*$//')"
version_at_least() {
  awk -v current="$1" -v required="$2" 'BEGIN {
    split(current, c, "."); split(required, r, ".")
    for (i = 1; i <= 3; i++) {
      if ((c[i] + 0) > (r[i] + 0)) exit 0
      if ((c[i] + 0) < (r[i] + 0)) exit 1
    }
    exit 0
  }'
}
if ! version_at_least "$compose_version" "2.24.4"; then
  printf '%s\n' "Docker Compose v2.24.4 or later is required" >&2
  exit 1
fi

if [ -n "${NEXTVISIT_LLM_DATA_PATH:-}" ]; then
  data_path="$NEXTVISIT_LLM_DATA_PATH"
else
  case "$(uname -s)" in
    Linux)
      data_path=/var/lib/docker
      [ -d "$data_path" ] || data_path=/
      ;;
    Darwin)
      data_path=.
      printf '%s\n' "warning: verify Docker Desktop disk image capacity manually" >&2
      ;;
    *) data_path=. ;;
  esac
fi
available_kib="$(df -Pk "$data_path" | awk 'NR == 2 {print $4}')"
minimum_kib=20971520
if [ "$available_kib" -lt "$minimum_kib" ]; then
  printf '%s\n' "at least 20 GiB of free disk is required" >&2
  exit 1
fi

if [ "$mode" = "gpu" ]; then
  command -v nvidia-smi >/dev/null 2>&1 || {
    printf '%s\n' "nvidia-smi is required for gpu mode" >&2
    exit 1
  }
  command -v nvidia-ctk >/dev/null 2>&1 || {
    printf '%s\n' "nvidia-ctk is required for gpu mode" >&2
    exit 1
  }
  nvidia-smi >/dev/null
  docker info --format '{{json .Runtimes}}' | grep -q '"nvidia"' || {
    printf '%s\n' "Docker nvidia runtime is not configured" >&2
    exit 1
  }
fi

printf '%s\n' "host verification passed for $mode mode"
```

Run:

```bash
chmod +x infra/llm/scripts/verify-host.sh
shellcheck infra/llm/scripts/verify-host.sh
infra/llm/scripts/verify-host.sh cpu
```

Expected now: the macOS host passes CPU checks. Do not run `gpu` until the Ubuntu/NVIDIA environment exists.

- [ ] **Step 2: Add the NVIDIA reservation override**

Create `compose.gpu.yml`:

```yaml
services:
  ollama:
    deploy:
      resources:
        reservations:
          devices:
            - driver: nvidia
              count: 1
              capabilities: [gpu]
```

- [ ] **Step 3: Add the no-host-port Tunnel override**

Create `compose.tunnel.yml`:

```yaml
services:
  ollama:
    ports: !reset []

  cloudflared:
    image: cloudflare/cloudflared:2026.8.3@sha256:51c9cefcb4569df44e1ad403ab1d3d8065aa8e84339bcfc6aee75502e1140339
    command: ["tunnel", "--no-autoupdate", "run"]
    env_file:
      - "${NEXTVISIT_LLM_ENV_FILE:?Set NEXTVISIT_LLM_ENV_FILE to /etc/nextvisit/llm.env}"
    depends_on:
      ollama:
        condition: service_healthy
    restart: unless-stopped
```

The environment file contains only `TUNNEL_TOKEN`. Cloudflare Access client ID and secret belong on the Spring API host and never on the laptop.

- [ ] **Step 4: Prove all variants render and the final variant publishes no port**

Run:

```bash
cd infra/llm
tunnel_env="$(mktemp)"
rendered="$(mktemp)"
trap 'rm -f "$tunnel_env" "$rendered"' EXIT
printf '%s\n' 'TUNNEL_TOKEN=render-only-value' >"$tunnel_env"
chmod 600 "$tunnel_env" "$rendered"
docker compose -f compose.yml config --quiet
docker compose -f compose.yml -f compose.gpu.yml config --quiet
NEXTVISIT_LLM_ENV_FILE="$tunnel_env" docker compose -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml config --quiet
NEXTVISIT_LLM_ENV_FILE="$tunnel_env" docker compose -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml config --format json >"$rendered"
jq -e '.services.ollama.ports == null' "$rendered" >/dev/null
```

Expected: every render exits zero and the JSON assertion confirms that the final service has no published port.

- [ ] **Step 5: Document local operation and the deferred laptop runbook**

Create `infra/llm/README.md` with these sections and commands:

````markdown
# NextVisit LLM operations

The required image is Ollama `0.33.3`; Docker Compose v2.24.4 or later is
required. The default model is `qwen3:4b-q8_0`
with a 2,048-token context. The named volume `nextvisit-llm-ollama-data`
survives container replacement.

## macOS CPU development

```bash
cp .env.example .env
docker compose -f compose.yml up --detach --wait --build ollama
docker compose -f compose.yml run --rm model-init
./scripts/smoke-openai.sh http://127.0.0.1:11434/v1
docker compose -f compose.yml down
```

`model-init` downloads about 4.4 GB only when the configured tag is missing.
Omit that command when checking container health without downloading the model.
Never use `docker compose down --volumes` for routine deployment.

## Ubuntu laptop preparation — deferred until hardware is ready

1. Install Ubuntu Server, the NVIDIA driver recommended for the laptop,
   Docker Engine with the Compose plugin, and NVIDIA Container Toolkit.
2. Create the dedicated runner account, grant its required Docker access,
   configure Docker's NVIDIA runtime, and reboot:

   ```bash
   sudo useradd --create-home --shell /bin/bash nextvisit-runner
   sudo usermod --append --groups docker nextvisit-runner
   sudo nvidia-ctk runtime configure --runtime=docker
   sudo systemctl restart docker
   sudo reboot
   ```

3. The repository-scoped Actions runner checks out each triggering commit with
   its ephemeral `contents: read` token and needs no deploy-capable Git
   credential. For manual checks only, use a read-only private-repository clone
   owned by `nextvisit-runner`, then run:

   ```bash
   cd infra/llm
   ./scripts/verify-host.sh gpu
   ```

4. In Cloudflare Zero Trust, create a remotely managed Tunnel route whose
   service is `http://ollama:11434`. Protect its public hostname with an Access
   service-token policy.
5. Store only the Tunnel token locally:

   ```bash
   sudo install -d -o root -g nextvisit-runner -m 0750 /etc/nextvisit
   sudo install -o nextvisit-runner -g nextvisit-runner -m 0600 /dev/null /etc/nextvisit/llm.env
   sudoedit /etc/nextvisit/llm.env
   sudo test "$(stat -c '%a' /etc/nextvisit/llm.env)" = 600
   sudo -u nextvisit-runner test -r /etc/nextvisit/llm.env
   ```

   The file has one line named `TUNNEL_TOKEN` whose value is issued by
   Cloudflare. Do not store Access credentials in this file.
6. Protect `main` in the repository settings by requiring pull-request review
   and the `LLM CI` checks before merge.
7. In `https://github.com/y-minion/wanted_Hackaton/settings/actions/runners`,
   create a repository-only Linux x64 runner, execute GitHub's generated setup
   commands as `nextvisit-runner`, add the `llm` custom label during
   `config.sh`, and install/start it with `sudo ./svc.sh install nextvisit-runner`
   followed by `sudo ./svc.sh start`.
8. Create the GitHub environment `llm-laptop`. Keep required reviewers on it
   if the account plan supports them.
9. Leave the repository variable `LLM_DEPLOY_ENABLED` absent or `false` until
   every preceding check passes. Set it to `true` only when automatic `main`
   deployments should begin.

## Manual production-equivalent start

```bash
cd infra/llm
export NEXTVISIT_LLM_ENV_FILE=/etc/nextvisit/llm.env
docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml up --detach --wait --build ollama
docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml run --rm model-init
./scripts/smoke-openai.sh http://127.0.0.1:11434/v1
docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml up --detach ollama cloudflared
ollama_id="$(docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml ps -q ollama)"
test -n "$ollama_id"
test "$(docker inspect -f '{{len .HostConfig.PortBindings}}' "$ollama_id")" = 0
```

The first start exposes only loopback for the local smoke. The final command
recreates Ollama with no published host port and connects it to `cloudflared`.

## Deferred hardware acceptance

After the final start, run `nvidia-smi`, then
`docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml exec ollama ollama ps`.
Record `100% GPU`, response time with the 2K context, behavior after a reboot,
runner service status, Tunnel reachability through Access, and volume reuse.
These results cannot be claimed from macOS and remain open until the laptop is ready.

## Secrets and logs

Never print the Tunnel token, Access service-token values, Authorization
headers, prompts, provider response bodies, generated sentences, or guardian
data. The smoke script reports only fixed status text.
````

When writing the nested Markdown fences in the real file, use ordinary fenced blocks; the content above is the complete runbook text.

- [ ] **Step 6: Run shell and Compose static acceptance**

Run:

```bash
shellcheck infra/llm/scripts/*.sh infra/llm/tests/*.sh
cd infra/llm/tests && bash ensure-model-test.sh && cd ../../..
cd infra/llm
tunnel_env="$(mktemp)"
rendered="$(mktemp)"
trap 'rm -f "$tunnel_env" "$rendered"' EXIT
printf '%s\n' 'TUNNEL_TOKEN=render-only-value' >"$tunnel_env"
chmod 600 "$tunnel_env" "$rendered"
docker compose -f compose.yml config --quiet
docker compose -f compose.yml -f compose.gpu.yml config --quiet
NEXTVISIT_LLM_ENV_FILE="$tunnel_env" docker compose -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml config --quiet
NEXTVISIT_LLM_ENV_FILE="$tunnel_env" docker compose -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml config --format json >"$rendered"
jq -e '.services.ollama.ports == null' "$rendered" >/dev/null
```

Expected: shell tests and all three renders pass. Ubuntu GPU execution remains explicitly unrun.

- [ ] **Step 7: Commit production variants and operations guide**

```bash
git add infra/llm
git commit -m "feat: GPU와 Cloudflare Tunnel 배포 구성"
```

### Task 11: Add hosted CI and guarded private-repository deployment

**Files:**
- Create: `infra/llm/tests/workflows-test.sh`
- Create: `.github/workflows/llm-ci.yml`
- Create: `.github/workflows/llm-deploy.yml`

**Interfaces:**
- Consumes: Gradle wrapper, Task 9 shell tests/build, Task 10 Compose variants, `/etc/nextvisit/llm.env`, repository variable `LLM_DEPLOY_ENABLED`, environment `llm-laptop`, and runner labels `[self-hosted, linux, llm]`.
- Produces: GitHub-hosted CI for pushes/PRs and a `main`-only, disabled-by-default deployment that preserves the model volume and never executes PR code on the laptop.

- [ ] **Step 1: Write static workflow security assertions before creating workflows**

Create `infra/llm/tests/workflows-test.sh`:

```sh
#!/bin/sh
set -eu

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH= cd -- "$script_dir/../../.." && pwd)"
ci="$repo_root/.github/workflows/llm-ci.yml"
deploy="$repo_root/.github/workflows/llm-deploy.yml"

test -f "$ci"
test -f "$deploy"

grep -F 'contents: read' "$ci" >/dev/null
grep -F 'contents: read' "$deploy" >/dev/null
grep -F 'persist-credentials: false' "$ci" >/dev/null
grep -F 'persist-credentials: false' "$deploy" >/dev/null
grep -F 'runs-on: [self-hosted, linux, llm]' "$deploy" >/dev/null
grep -F "vars.LLM_DEPLOY_ENABLED == 'true'" "$deploy" >/dev/null
grep -F "github.ref == 'refs/heads/main'" "$deploy" >/dev/null
grep -F 'environment: llm-laptop' "$deploy" >/dev/null
grep -F 'cancel-in-progress: false' "$deploy" >/dev/null
grep -F 'uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262' "$ci" >/dev/null
grep -F 'uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262' "$deploy" >/dev/null
grep -F 'uses: actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3' "$ci" >/dev/null
grep -F 'ref: ${{ github.sha }}' "$deploy" >/dev/null
grep -F '.HostConfig.PortBindings' "$deploy" >/dev/null

if grep -F 'pull_request_target' "$ci" "$deploy" >/dev/null; then
  printf '%s\n' "pull_request_target is forbidden" >&2
  exit 1
fi
if grep -F 'self-hosted' "$ci" >/dev/null; then
  printf '%s\n' "CI must not use the laptop runner" >&2
  exit 1
fi
if grep -F 'pull_request:' "$deploy" >/dev/null; then
  printf '%s\n' "deploy must not accept pull requests" >&2
  exit 1
fi
if grep -F 'ref: main' "$deploy" >/dev/null; then
  printf '%s\n' "deploy must check out the triggering commit, not moving main" >&2
  exit 1
fi

printf '%s\n' "workflow security tests passed"
```

Mark it executable, then run from the repository root:

```bash
chmod +x infra/llm/tests/workflows-test.sh
./infra/llm/tests/workflows-test.sh
```

Expected: failure at the first `test -f` because the workflows do not yet exist.

- [ ] **Step 2: Create GitHub-hosted LLM CI**

Create `.github/workflows/llm-ci.yml`:

```yaml
name: LLM CI

on:
  push:
    branches: [main, feat/llm-server]
    paths:
      - "backend/**"
      - "infra/llm/**"
      - ".github/workflows/llm-*.yml"
  pull_request:
    paths:
      - "backend/**"
      - "infra/llm/**"
      - ".github/workflows/llm-*.yml"

permissions:
  contents: read

concurrency:
  group: llm-ci-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: true

jobs:
  backend:
    runs-on: ubuntu-latest
    steps:
      - name: Check out repository
        uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
        with:
          persist-credentials: false
      - name: Set up Java 21
        uses: actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3 # v4
        with:
          distribution: temurin
          java-version: "21"
          cache: gradle
          cache-dependency-path: |
            backend/*.gradle.kts
            backend/gradle/wrapper/gradle-wrapper.properties
            backend/*/build.gradle.kts
      - name: Run backend tests
        working-directory: backend
        run: ./gradlew --no-daemon test

  container:
    runs-on: ubuntu-latest
    steps:
      - name: Check out repository
        uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
        with:
          persist-credentials: false
      - name: Check shell scripts
        run: shellcheck infra/llm/scripts/*.sh infra/llm/tests/*.sh
      - name: Run shell unit tests
        run: |
          bash infra/llm/tests/ensure-model-test.sh
          sh infra/llm/tests/workflows-test.sh
      - name: Build pinned Ollama image
        working-directory: infra/llm
        run: docker build --tag nextvisit-ollama:0.33.3 .
      - name: Render CPU, GPU, and Tunnel Compose variants
        working-directory: infra/llm
        run: |
          tunnel_env="$RUNNER_TEMP/llm-tunnel.env"
          rendered="$RUNNER_TEMP/llm-compose.json"
          trap 'rm -f "$tunnel_env" "$rendered"' EXIT
          printf '%s\n' 'TUNNEL_TOKEN=ci-render-only-value' >"$tunnel_env"
          touch "$rendered"
          chmod 600 "$tunnel_env" "$rendered"
          docker compose -f compose.yml config --quiet
          docker compose -f compose.yml -f compose.gpu.yml config --quiet
          NEXTVISIT_LLM_ENV_FILE="$tunnel_env" docker compose -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml config --quiet
          NEXTVISIT_LLM_ENV_FILE="$tunnel_env" docker compose -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml config --format json >"$rendered"
          jq -e '.services.ollama.ports == null' "$rendered" >/dev/null
```

This workflow builds only the server image. No step starts `model-init` or downloads `qwen3:4b-q8_0`.

- [ ] **Step 3: Create the disabled-by-default laptop deployment**

Create `.github/workflows/llm-deploy.yml`:

```yaml
name: Deploy LLM Laptop

on:
  push:
    branches: [main]
    paths:
      - "infra/llm/**"
      - ".github/workflows/llm-deploy.yml"
  workflow_dispatch:

permissions:
  contents: read

concurrency:
  group: llm-laptop-deploy
  cancel-in-progress: false

jobs:
  deploy:
    if: ${{ github.ref == 'refs/heads/main' && vars.LLM_DEPLOY_ENABLED == 'true' }}
    runs-on: [self-hosted, linux, llm]
    environment: llm-laptop
    timeout-minutes: 90
    steps:
      - name: Check out triggering main commit without stored credentials
        uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262 # v4
        with:
          ref: ${{ github.sha }}
          clean: true
          persist-credentials: false

      - name: Verify Ubuntu GPU host
        run: infra/llm/scripts/verify-host.sh gpu

      - name: Verify local Tunnel environment file
        run: |
          test -r /etc/nextvisit/llm.env
          test "$(stat -c '%a' /etc/nextvisit/llm.env)" = 600

      - name: Build, initialize, and smoke Ollama on loopback
        working-directory: infra/llm
        run: |
          docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml build ollama
          docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml up --detach --wait ollama
          docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml run --rm model-init
          ./scripts/smoke-openai.sh http://127.0.0.1:11434/v1

      - name: Apply final no-port Tunnel configuration
        working-directory: infra/llm
        env:
          NEXTVISIT_LLM_ENV_FILE: /etc/nextvisit/llm.env
        run: |
          rendered="$RUNNER_TEMP/llm-compose.json"
          trap 'rm -f "$rendered"' EXIT
          touch "$rendered"
          chmod 600 "$rendered"
          docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml up --detach --wait ollama cloudflared
          docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml config --format json >"$rendered"
          jq -e '.services.ollama.ports == null' "$rendered" >/dev/null
          ollama_id="$(docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml ps -q ollama)"
          test -n "$ollama_id"
          test "$(docker inspect -f '{{len .HostConfig.PortBindings}}' "$ollama_id")" = 0
          test "$(docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml ps --status running --services | grep -Ec '^(ollama|cloudflared)$')" = 2
```

There is no prune, volume removal, response dump, credential echo, remote Access request, or failure-time container log dump. The local smoke occurs before the final configuration recreates Ollama without a published port.

- [ ] **Step 4: Run workflow security and shell checks**

Run from the repository root:

```bash
shellcheck infra/llm/scripts/*.sh infra/llm/tests/*.sh
./infra/llm/tests/ensure-model-test.sh
./infra/llm/tests/workflows-test.sh
rg -n "pull_request_target|runs-on:.*self-hosted" .github/workflows
```

Expected: both shell tests pass. The final search reports `self-hosted` only in `llm-deploy.yml` and reports no `pull_request_target` line.

- [ ] **Step 5: Commit CI and deployment workflows**

```bash
git add .github/workflows/llm-ci.yml .github/workflows/llm-deploy.yml infra/llm/tests/workflows-test.sh
git commit -m "ci: 비공개 저장소 LLM 노트북 배포 구성"
```

### Task 12: Update project truth and run complete local acceptance

**Files:**
- Modify: `README.md`
- Modify: `backend/README.md`

**Interfaces:**
- Consumes: every backend, container, security, and workflow contract from Tasks 1–11.
- Produces: repository documentation that points to `infra/llm/README.md`, describes template-first asynchronous behavior, replaces the obsolete Ollama/cloudflared systemd claim, and separates current macOS acceptance from deferred Ubuntu/NVIDIA acceptance.

- [ ] **Step 1: Confirm the existing docs still describe the pre-LLM state**

Run:

```bash
rg -n "LLM 연동 전체|미착수|systemd 서비스|금지 어휘 확장" README.md backend/README.md
```

Expected: matches show that `backend/README.md` still calls the LLM and vocabulary work unfinished and top-level `README.md` still says Ollama and cloudflared are host systemd services.

- [ ] **Step 2: Replace the top-level deployment and guardrail text**

Make these exact semantic changes in `README.md`:

1. Replace the laptop systemd sentence with:

```markdown
노트북은 Ubuntu Server에서 NVIDIA 드라이버와 Docker/NVIDIA Container Toolkit을 사용합니다.
Ollama와 cloudflared는 `infra/llm`의 Compose 서비스로 실행하고, 저장소 전용 GitHub Actions runner만 Linux 서비스로 둡니다.
현재 코드는 CPU·GPU·Tunnel 구성을 갖췄지만 Ubuntu/NVIDIA 실기 검증과 자동 배포 활성화는 노트북 준비 뒤 진행합니다.
상세 순서는 `infra/llm/README.md`에 있습니다.
```

2. Replace the three-line LLM guardrail code block with:

```text
1. 응답은 정확히 하나의 JSON 객체이고 questions/rank/sentence 외 필드가 없어야 함
2. 질문 수와 rank 집합이 입력과 정확히 같아야 함 — 일부만 통과해도 전체 거부
3. 각 문장은 NFC 정규화 뒤 1~200자, 물음표 하나로 끝나고 마크다운·행동 지시가 없어야 함
4. 개선·악화·호전·위험·정상·비정상·회복·좋아지·좋아졌·나빠지·나빠졌·나아지·나아졌·좋아져·나빠져·나아져·좋아짐·나빠짐·나아짐·재활·치료·낙상·점수·처방·운동·진단·기능검사·병원에 가·받으셔야·하셔야를 코드로 거부
5. 입력 템플릿의 모든 아라비아 숫자 토큰을 같은 횟수로 보존해야 함
6. 최초 호출 + 재생성 2회가 모두 실패하면 전체 LLM 출력을 버리고 템플릿 본문을 유지
```

3. Replace the `infra/**` technology line with:

```markdown
**infra/** — 개발용 Postgres Compose와 별도로 `infra/llm`에 Ollama 0.33.3 CPU/GPU, 모델 초기화, Cloudflare Tunnel, smoke, GitHub Actions 배포 구성이 있습니다. 노트북 배포는 `LLM_DEPLOY_ENABLED=true` 전까지 실행되지 않습니다.
```

4. Under local execution, add:

```markdown
LLM은 기본 비활성이므로 API 개발에 Ollama가 필요하지 않습니다. 로컬 CPU 컨테이너와 실제 OpenAI 호환 smoke는 `infra/llm/README.md`를 따릅니다.
```

- [ ] **Step 3: Replace backend implementation status with the implemented architecture**

Apply these exact content updates in `backend/README.md`:

```markdown
# 백엔드 — 구현된 것

2026-09-07 기준. 규칙 엔진, API core, LLM 질문 다듬기와 노트북용 컨테이너·파이프라인 구성이 구현됐습니다. Ubuntu/NVIDIA 실기 검증과 배포 활성화만 장비 준비 뒤 남습니다.
```

Add these two document rows:

```markdown
| `../docs/superpowers/specs/2026-09-07-llm-server-design.md` | LLM, Docker, Tunnel, CI/CD의 승인된 계약 |
| `../infra/llm/README.md` | macOS CPU 실행과 향후 Ubuntu/NVIDIA 운영 절차 |
```

Replace the status table with:

```markdown
| 단계 | 상태 |
| --- | --- |
| `engine` — 판정 규칙 엔진 | ✅ 완료 |
| `api` (api-core) — 저장·조회·화면 데이터 | ✅ 완료 |
| **api-llm — 비동기 문장 다듬기·검증·전체 폴백** | ✅ 코드·로컬 검증 완료 |
| frontend — React PWA | 별도 브랜치에서 병렬 작업 중 |
| infra — Ollama CPU/GPU·Tunnel·CI/CD | ✅ 구성 완료, Ubuntu/NVIDIA 실기 대기 |
```

Replace the obsolete LLM paragraph in the regulation section with:

```markdown
LLM 출력은 같은 구조 경계 안으로 들어옵니다. 엔진 템플릿을 먼저 `LLM_PENDING`으로 저장한 뒤, 최소 입력만 비동기로 보내고 `QuestionOutputGuard`가 JSON 구조, rank, 길이, 질문형, 금지 표현, 행동 지시와 숫자 보존을 전부 확인합니다. 하나라도 실패하면 전체 batch를 버리고 템플릿을 유지합니다. 늦은 응답은 `generation_id` 조건부 갱신이 차단합니다.
```

Replace the LLM, Docker, and vocabulary bullets under “아직 없는 것” with:

```markdown
- **Ubuntu/NVIDIA 실기 결과.** 장비 준비 뒤 GPU 적재, 2K 컨텍스트 응답 시간, 재부팅 복구, runner와 Tunnel을 검증합니다.
- **재시작 뒤 `LLM_PENDING` 자동 복구.** 현재는 다음 기록 갱신 때 다시 시도하며 durable queue는 MVP 범위 밖입니다.
```

Add this disabled/default run block under “돌려보기”:

```bash
# 기본값: 안전한 템플릿만 사용, Ollama 불필요
NEXTVISIT_LLM_ENABLED=false ./gradlew :api:bootRun

# Ollama 또는 OpenAI 호환 서버를 사용할 때
NEXTVISIT_LLM_ENABLED=true \
NEXTVISIT_LLM_BASE_URL=http://localhost:11434/v1 \
NEXTVISIT_LLM_MODEL=qwen3:4b-q8_0 \
./gradlew :api:bootRun
```

Do not rewrite frontend implementation details beyond marking that work as parallel; those files and their current branch belong to the other active workstream.

- [ ] **Step 4: Verify documentation no longer claims the obsolete state**

Run:

```bash
if rg -n "LLM 연동 전체.*없|api-llm.*미착수|Ollama \+ cloudflared를 systemd|금지 어휘 확장.*대기" README.md backend/README.md; then
  exit 1
fi
rg -n "generation_id|LLM_PENDING|infra/llm/README.md|LLM_DEPLOY_ENABLED" README.md backend/README.md
```

Expected: the obsolete-state check prints nothing and exits zero; the second search finds the new implementation and deferred-activation text.

- [ ] **Step 5: Invoke the completion-verification discipline and run all Java tests**

Before making any completion claim, invoke `superpowers:verification-before-completion`. Then run:

```bash
cd backend
env JAVA_HOME=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home ./gradlew clean test
cd ..
```

Expected: all engine and API tests pass from a clean build, including disabled defaults, fake-provider HTTP, guard failures, retries, commit/rollback timing, queue rejection, stale generation, and metadata-only logging.

- [ ] **Step 6: Run static infrastructure and workflow acceptance**

Run:

```bash
shellcheck infra/llm/scripts/*.sh infra/llm/tests/*.sh
./infra/llm/tests/ensure-model-test.sh
./infra/llm/tests/workflows-test.sh
cd infra/llm
tunnel_env="$(mktemp)"
rendered="$(mktemp)"
trap 'rm -f "$tunnel_env" "$rendered"' EXIT
printf '%s\n' 'TUNNEL_TOKEN=render-only-value' >"$tunnel_env"
chmod 600 "$tunnel_env" "$rendered"
docker build --tag nextvisit-ollama:0.33.3 .
docker compose -f compose.yml config --quiet
docker compose -f compose.yml -f compose.gpu.yml config --quiet
NEXTVISIT_LLM_ENV_FILE="$tunnel_env" docker compose -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml config --quiet
NEXTVISIT_LLM_ENV_FILE="$tunnel_env" docker compose -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml config --format json >"$rendered"
jq -e '.services.ollama.ports == null' "$rendered" >/dev/null
cd ../..
```

Expected: shell checks, fake model lifecycle, private-runner invariants, pinned image build, all Compose renders, and the no-port production assertion pass without pulling a model.

- [ ] **Step 7: Run the current macOS CPU container and real OpenAI smoke**

The first execution downloads approximately 4.4 GB into the named volume. Run:

```bash
cd infra/llm
./scripts/verify-host.sh cpu
docker compose --project-name nextvisit-llm -f compose.yml up --detach --wait --build ollama
docker compose --project-name nextvisit-llm -f compose.yml run --rm model-init
./scripts/smoke-openai.sh http://127.0.0.1:11434/v1
docker compose --project-name nextvisit-llm -f compose.yml down
cd ../..
```

Expected: host check, Ollama health, idempotent model initialization, and `/v1/chat/completions` JSON smoke pass. The final `down` preserves `nextvisit-llm-ollama-data`.

- [ ] **Step 8: Check scope, secrets, whitespace, and branch state**

Run:

```bash
git diff --check
git check-ignore infra/llm/.env
test "$(git branch --show-current)" = "feat/llm-server"
test -z "$(git diff --name-only main...HEAD -- frontend)"
git status --short
```

Expected: no whitespace errors, `.env` is ignored, the branch is correct, no frontend path changed, and status lists only the two documentation files before the final commit.

- [ ] **Step 9: Commit documentation after fresh acceptance evidence**

```bash
git add README.md backend/README.md
git commit -m "docs: LLM 운영과 검증 상태 반영"
```

- [ ] **Step 10: Request an independent final review**

Invoke `superpowers:requesting-code-review` against `main...feat/llm-server`. The review gate must explicitly inspect:

```text
- No raw guardian/free-note/case data reaches QuestionRewritePrompt.
- No prompt, response, sentence, credential, or case ID reaches LLM logs.
- All completion/failure writes require case ID + generation ID + LLM_PENDING.
- Provider calls occur outside database transactions and only after commit.
- Disabled mode has no LlmClient or executor and preserves existing template output.
- PR jobs use only GitHub-hosted runners; deploy has no PR trigger and is variable-gated.
- Final Tunnel Compose publishes no host port and no command removes the model volume.
- Ubuntu/NVIDIA results are described as deferred and are not reported as passing.
```

If the review finds a violation, return to the task that owns that invariant, add a focused failing test, make the smallest correction, rerun that task’s command plus Steps 5–8, and commit the correction before requesting review again.

## Acceptance Traceability

| Approved requirement | Owning task and evidence |
| --- | --- |
| Shared prohibited terms and safe templates | Task 1 `TemplatesTest` |
| Four cache states, UUID generation, no stale overwrite | Task 2 persistence tests |
| Disabled defaults, timeout settings, worker 1/queue 32 | Task 3 configuration tests |
| Minimal prompt without raw records or IDs | Task 4 prompt tests |
| Exact JSON/ranks, 200 chars, one `?`, NFC, no markdown/directive, number multiset | Task 5 guard tests |
| OpenAI request, Bearer/Access headers, content-free errors | Task 6 mock HTTP tests |
| Three attempts, whole-batch success/fallback, no content logs | Task 7 coordinator tests |
| AFTER_COMMIT, rollback silence, pending template reads, queue rejection | Task 8 unit/integration tests |
| Pinned CPU server, named model volume, idempotent pull, silent smoke | Task 9 shell/Docker checks |
| NVIDIA reservation, no-port Tunnel, secret placement, deferred host checklist | Task 10 Compose assertions and runbook |
| Hosted PR CI, repository runner only for guarded main deploy | Task 11 workflow security test |
| Full clean suite, real macOS CPU smoke, documentation truth | Task 12 fresh acceptance commands |

The implementation is code-complete only after Task 12 passes on macOS. Ubuntu/NVIDIA hardware acceptance remains a named follow-up and `LLM_DEPLOY_ENABLED` remains absent or `false` until that follow-up is executed.

## Final-review safety amendment — 2026-09-08

This amendment supersedes the earlier repository-scoped runner wording without
rewriting the historical execution record.

- `QuestionOutputGuard` must fail closed: accept only the NFC-normalized
  template unchanged or the two enumerated final-clause joins
  (`습니다. `→`는데 ` and `입니다. `→`인데 `) with every other character
  preserved. The prompt and success fixtures follow the same finite boundary.
- `QuestionService.refresh` acquires the authoritative case-row write lock
  before reading snapshots, then writes the cache; `AFTER_COMMIT` publication
  and outer transaction semantics remain unchanged.
- Refresh and common error handlers log fixed opaque codes only. They do not
  attach case/request values, exception messages, or throwable objects; this
  intentionally trades detailed in-process diagnostics for privacy.
- The current personal-account repository must not register the laptop runner
  and keeps deployment disabled. Future CD requires a GitHub organization,
  `llm-production` runner group limited to exactly `<ORG>/<REPO>`,
  `restricted_to_workflows=true`, and exactly
  `<ORG>/<REPO>/.github/workflows/llm-deploy.yml@refs/heads/main`. If that
  external boundary is unavailable, remain disabled and seek approval for a
  separate pull-based design. The in-repository linter is defense in depth,
  not the pre-allocation boundary.
