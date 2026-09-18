# 보호자 기록 기반 질문 정리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

## 인수인계 (작업마다 이 칸을 갱신하고 같은 커밋에 넣는다)

다른 도구(ChatGPT/Codex 등)가 저장소만 보고 이어받을 수 있도록 유지한다.

- **현재 위치:** Task 9 완료
- **다음 할 일:** Task 10
- **브랜치:** `ops/single-host-completion` (작업 폴더 `.worktrees/warm-observation-ui`)
- **열린 결정:** 설계서 10절 (1) 금지어와 보호자 원문, (2) 생각 모드 기본값 — (2)는 Task 12에서 제품 소유자 판단을 받는다
- **운영 반영:** 아직 없음. 운영 호스트 변경은 제품 소유자가 sudo로 직접 실행한다(Task 13)

**Goal:** 매주 보호자가 남긴 원문과 규칙 엔진이 찾은 관찰 변화를 LLM이 모아, 보호자가 치료사에게 물을 질문을 근거와 함께 정리하고, 보호자가 그 목록을 고쳐 확정하게 한다.

**Architecture:** 저장 시 템플릿 질문을 먼저 캐시에 넣고, 원문이 있으면 비동기로 LLM 정리를 돌린다. 코드 검증기가 형식·근거·숫자·금지 표현을 확인해 통과한 질문만 캐시를 교체한다. 보호자가 저장한 목록은 케이스에 확정 목록으로 따로 두고, 새 정리안은 제안으로만 알린다.

**Tech Stack:** Java 21, Spring Boot 3.5, PostgreSQL/H2(Flyway), Ollama OpenAI 호환 API(`qwen3:4b-q4_K_M`), React 19, TanStack Query v5, Vitest + MSW, Playwright

**Spec:** `docs/superpowers/specs/2026-09-17-caregiver-question-synthesis-design.md`

## Global Constraints

- Node `22.22.2` (`export PATH="$HOME/.nvm/versions/node/v22.22.2/bin:$PATH"`), Java 21 (`export JAVA_HOME=$(/usr/libexec/java_home -v 21)`).
- 백엔드 테스트: `cd backend && ./gradlew clean test`. 프런트: `npm --prefix frontend test`, `npm --prefix frontend run typecheck`, `npm --prefix frontend run build`.
- API는 **추가만** 한다. 기존 `questions[].sentence/source/evidence`, `extraQuestions`, 치료사 `questions`/`extraQuestions`를 지우지 않는다.
- 판단 금지: 진단, 원인 단정, 점수, 운동·치료·약 권유, 호전/악화 판단. `Templates.containsForbiddenWord` 목록을 그대로 적용한다.
- 로그에는 실패 코드·시간·시도 수만 남긴다. 보호자 원문, 모델 입력·출력을 로그·커밋·문서에 남기지 않는다(합성 fixture는 예외).
- 색으로 상태를 표현하지 않는다. 본문 18px, 주요 버튼 최소 56px(기존 `btn` 클래스).
- 치료사 화면에서 `location.hash`를 바꾸지 않는다. 치료사 토큰이 fragment로 오기 때문이다(`frontend/src/lib/therapistToken.ts`).
- 커밋 메시지는 영어 conventional commits, 끝에 `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- **작업마다 커밋하고, 이 문서의 체크박스와 인수인계 칸을 같은 커밋에서 갱신한다.**

---

### Task 1: LLM 요청 형태 단순화와 생각 모드 설정값

오늘(2026-09-17) 들어간 예시 한 쌍(`d38ccc8`)을 걷어내고, `reasoning_effort`를 설정값으로 바꾼다.

**Files:**
- Create: `backend/api/src/main/java/nextvisit/api/llm/LlmPrompt.java`
- Modify: `backend/api/src/main/java/nextvisit/api/llm/LlmClient.java`
- Modify: `backend/api/src/main/java/nextvisit/api/llm/LlmProperties.java`
- Modify: `backend/api/src/main/java/nextvisit/api/llm/OpenAiCompatibleLlmClient.java`
- Modify: `backend/api/src/main/java/nextvisit/api/llm/QuestionRewritePrompt.java` (예시 제거, `LlmPrompt` 반환 — Task 5에서 파일째 삭제)
- Modify: `backend/api/src/main/java/nextvisit/api/llm/QuestionGenerationCoordinator.java` (호출부만)
- Modify: `backend/api/src/main/resources/application.yml`
- Modify: `infra/demo/compose.demo.yml`
- Test: `backend/api/src/test/java/nextvisit/api/llm/OpenAiCompatibleLlmClientTest.java`, `QuestionRewritePromptTest.java`, `QuestionGenerationCoordinatorTest.java`, `LlmConfigurationTest.java`

**Interfaces:**
- Produces: `record LlmPrompt(String systemMessage, String userMessage)`, `LlmClient.complete(LlmPrompt)`, `LlmProperties.reasoningEffort()`(String, 기본 `"none"`), `LlmProperties.synthesisMaxNoteChars()`(int, 기본 4000, 최소 500)

- [x] **Step 1: 실패하는 테스트 작성**

`OpenAiCompatibleLlmClientTest`에서 `disablesReasoningAndSendsTheDemonstrationBeforeTheRealInput`을 지우고 아래 두 테스트로 바꾼다. `properties(...)` 도우미는 새 필드를 받게 고친다.

```java
    @Test
    void sendsReasoningEffortFromPropertiesWithOnlySystemAndUserMessages() throws Exception {
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
            .andExpect(request -> {
                JsonNode body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                assertThat(body.get("reasoning_effort").asText()).isEqualTo("none");
                assertThat(body.at("/messages").size()).isEqualTo(2);
                assertThat(body.at("/messages/0/role").asText()).isEqualTo("system");
                assertThat(body.at("/messages/1/role").asText()).isEqualTo("user");
                assertThat(body.at("/messages/1/content").asText()).isEqualTo("real-user");
            })
            .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                MediaType.APPLICATION_JSON));

        client.complete(new LlmPrompt("system", "real-user"));
        server.verify();
    }

    @Test
    void omitsReasoningEffortWhenPropertyIsBlank() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer blankServer = MockRestServiceServer.bindTo(builder).build();
        OpenAiCompatibleLlmClient blankClient = new OpenAiCompatibleLlmClient(
            properties("test-key", "", "", ""), mapper, builder.build());
        blankServer.expect(requestTo("http://localhost:11434/v1/chat/completions"))
            .andExpect(request -> {
                JsonNode body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                assertThat(body.has("reasoning_effort")).isFalse();
            })
            .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                MediaType.APPLICATION_JSON));

        blankClient.complete(new LlmPrompt("system", "user"));
        blankServer.verify();
    }
```

도우미:

```java
    private static LlmProperties properties(String accessId, String accessSecret) {
        return properties("test-key", accessId, accessSecret, "none");
    }

    private static LlmProperties properties(String apiKey, String accessId, String accessSecret) {
        return properties(apiKey, accessId, accessSecret, "none");
    }

    private static LlmProperties properties(String apiKey, String accessId, String accessSecret, String effort) {
        return new LlmProperties(true, URI.create("http://localhost:11434/v1"),
            "qwen3:4b-q8_0", apiKey, accessId, accessSecret,
            Duration.ofSeconds(3), Duration.ofSeconds(45), 3, 512, effort, 4000);
    }
```

이 파일의 나머지 `client.complete(new QuestionRewritePrompt.Prompt(...))` 호출은 모두 `client.complete(new LlmPrompt(..., ...))`로 바꾼다.

`LlmConfigurationTest`에 기본값 검사를 추가한다(기존 기본값 검사 테스트 안에 두 줄):

```java
        assertThat(properties.reasoningEffort()).isEqualTo("none");
        assertThat(properties.synthesisMaxNoteChars()).isEqualTo(4000);
```

- [x] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew :api:test --tests "nextvisit.api.llm.*"`
Expected: 컴파일 실패 — `LlmPrompt`가 없고 `LlmProperties` 인자 수가 맞지 않는다.

- [x] **Step 3: 구현**

`LlmPrompt.java`:

```java
package nextvisit.api.llm;

/** 모델에 보내는 한 번의 요청. system 한 개와 user 한 개로 끝난다. */
public record LlmPrompt(String systemMessage, String userMessage) {}
```

`LlmClient.java`:

```java
package nextvisit.api.llm;

public interface LlmClient {
    String complete(LlmPrompt prompt);
}
```

`LlmProperties.java`의 마지막 인자 뒤에 두 개를 추가한다.

```java
    @Min(1) @DefaultValue("3000") int maxOutputTokens,
    @DefaultValue("none") String reasoningEffort,
    @Min(500) @DefaultValue("4000") int synthesisMaxNoteChars
) {}
```

`OpenAiCompatibleLlmClient.java`: `REASONING_EFFORT` 상수와 `ArrayList` import, 예시 반복문을 지우고 `complete`의 앞부분을 이렇게 바꾼다.

```java
    @Override
    public String complete(LlmPrompt prompt) {
        // Ollama OpenAI 호환 엔드포인트에서 qwen3의 생각을 끄는 방법은 reasoning_effort="none"뿐이다
        // (docs/qa/2026-09-17-llm-activation.md 9절). 값이 비어 있으면 필드를 보내지 않는다.
        String effort = StringUtils.hasText(properties.reasoningEffort()) ? properties.reasoningEffort() : null;
        Request body = new Request(properties.model(), false, 0.1, 0,
            properties.maxOutputTokens(), new ResponseFormat("json_object"), effort,
            List.of(new Message("system", prompt.systemMessage()),
                new Message("user", prompt.userMessage())));
```

`Request` 레코드의 필드에 `@JsonInclude`를 붙인다(`import com.fasterxml.jackson.annotation.JsonInclude;`).

```java
        @JsonInclude(JsonInclude.Include.NON_NULL)
        @JsonProperty("reasoning_effort") String reasoningEffort,
```

`QuestionRewritePrompt.java`: `EXAMPLE_INPUT`, `EXAMPLE_OUTPUT`, `examples` 필드, `Example` 레코드, `Prompt` 레코드, 그리고 시스템 문구의 "반드시 한 번 적용하세요" 줄을 지운다. `build`는 `LlmPrompt`를 돌려준다.

```java
    public LlmPrompt build(List<QuestionCacheBody.Q> questions, Optional<String> retryRule) {
        List<InputQuestion> inputs = questions.stream()
            .map(q -> new InputQuestion(q.rank(), q.templateSentence()))
            .toList();
        String systemMessage = retryRule
            .map(rule -> SYSTEM + "\n직전 응답은 검증 규칙 " + rule + "을 위반했습니다. 이 규칙을 지켜 다시 생성하세요.")
            .orElse(SYSTEM);
        return new LlmPrompt(systemMessage, json(new InputPayload(inputs)));
    }
```

`QuestionGenerationCoordinator.java`: `QuestionRewritePrompt.Prompt prompt = ...`를 `LlmPrompt prompt = ...`로 바꾼다.

`QuestionRewritePromptTest.java`: 예시·강제 연결을 검사하던 테스트(`d38ccc8`에서 추가된 것)를 지우고, 남은 테스트의 `prompt.systemMessage()`/`prompt.userMessage()` 사용은 그대로 둔다. `QuestionGenerationCoordinatorTest`의 `new LlmProperties(...)`에 `"none", 4000`을 덧붙인다.

`application.yml`의 `nextvisit.llm` 아래에 추가:

```yaml
    reasoning-effort: ${NEXTVISIT_LLM_REASONING_EFFORT:none}
    synthesis-max-note-chars: ${NEXTVISIT_LLM_SYNTHESIS_MAX_NOTE_CHARS:4000}
```

`infra/demo/compose.demo.yml`의 api `environment`에 `NEXTVISIT_LLM_READ_TIMEOUT` 줄 아래로 추가:

```yaml
      NEXTVISIT_LLM_REASONING_EFFORT: "${NEXTVISIT_LLM_REASONING_EFFORT:-none}"
```

- [x] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew :api:test --tests "nextvisit.api.llm.*"`
Expected: PASS

- [x] **Step 5: 인수인계 칸 갱신 후 커밋**

인수인계 칸을 "현재 위치: Task 1 완료 / 다음 할 일: Task 2"로 바꾸고 이 Task의 체크박스를 `[x]`로 바꾼다.

```bash
git add backend infra/demo/compose.demo.yml docs/superpowers/plans/2026-09-18-caregiver-question-synthesis.md
git commit -m "refactor: send one system and one user message and make reasoning effort configurable"
```

---

### Task 2: 정리 입력 조립 (`SynthesisInputAssembler`)

**Files:**
- Create: `backend/api/src/main/java/nextvisit/api/llm/SynthesisInput.java`
- Create: `backend/api/src/main/java/nextvisit/api/llm/SynthesisInputAssembler.java`
- Test: `backend/api/src/test/java/nextvisit/api/llm/SynthesisInputAssemblerTest.java`

**Interfaces:**
- Consumes: `LlmProperties.synthesisMaxNoteChars()` (Task 1), `QuestionCacheBody.Q.rank()/templateSentence()`, `SnapshotBody`
- Produces:
  - `record SynthesisInput(List<Detection> detections, List<NoteLine> notes)` + `detectionIds()`(Set&lt;String&gt;), `noteWeeks()`(Set&lt;Integer&gt;), `hasNotes()`
  - `record SynthesisInput.Detection(String id, int rank, String sentence)` — id는 `"D" + rank`
  - `record SynthesisInput.NoteLine(int week, String timeTagLabel, String itemLabel, String text)`
  - `SynthesisInput SynthesisInputAssembler.assemble(List<QuestionCacheBody.Q> templates, SortedMap<Integer, SnapshotBody> bodiesByWeek)`
  - `static List<SynthesisInput.NoteLine> SynthesisInputAssembler.noteLines(int week, SnapshotBody body)`
  - `static boolean SynthesisInputAssembler.hasNote(SnapshotBody body)`

- [x] **Step 1: 실패하는 테스트 작성**

```java
package nextvisit.api.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import nextvisit.api.questions.QuestionCacheBody;
import nextvisit.api.snapshots.SnapshotBody;
import nextvisit.engine.ObservationSet;
import org.junit.jupiter.api.Test;

class SynthesisInputAssemblerTest {

    private static LlmProperties props(int maxChars) {
        return new LlmProperties(true, URI.create("http://localhost:11434/v1"), "qwen3:4b-q4_K_M",
            "ollama", "", "", Duration.ofSeconds(3), Duration.ofSeconds(45), 3, 3000, "none", maxChars);
    }

    private static SnapshotBody body(String free, String tag, Map<String, String> itemNotes) {
        Map<String, SnapshotBody.ItemValues> items = new LinkedHashMap<>();
        itemNotes.forEach((code, note) -> items.put(code, new SnapshotBody.ItemValues(null, null, null, null, note)));
        return new SnapshotBody(items, Map.of(), null, free == null ? null : new SnapshotBody.FreeNote(free, tag));
    }

    private static QuestionCacheBody.Q template(int rank, String sentence) {
        return new QuestionCacheBody.Q(rank, "STALL", List.of("ambulation"), null, sentence, sentence, "TEMPLATE");
    }

    @Test
    void numbersDetectionsByRank() {
        SortedMap<Integer, SnapshotBody> bodies = new TreeMap<>(Map.of(3, body("합성 메모", "MORNING", Map.of())));
        SynthesisInput input = new SynthesisInputAssembler(props(4000))
            .assemble(List.of(template(1, "가?"), template(2, "나?")), bodies);

        assertThat(input.detections()).extracting(SynthesisInput.Detection::id).containsExactly("D1", "D2");
        assertThat(input.detections().get(1).sentence()).isEqualTo("나?");
        assertThat(input.detectionIds()).containsExactly("D1", "D2");
    }

    @Test
    void includesFreeNoteWithTimeTagAndItemNotesWithLabels() {
        SortedMap<Integer, SnapshotBody> bodies = new TreeMap<>();
        bodies.put(3, body("  합성: 오후에 어깨를 자주 만지심  ", "AFTERNOON", Map.of("toilet", "합성: 밤에 한 번 깸")));
        SynthesisInput input = new SynthesisInputAssembler(props(4000)).assemble(List.of(), bodies);

        assertThat(input.notes()).containsExactly(
            new SynthesisInput.NoteLine(3, "오후", null, "합성: 오후에 어깨를 자주 만지심"),
            new SynthesisInput.NoteLine(3, null, ObservationSet.STROKE.item("toilet").label(), "합성: 밤에 한 번 깸"));
        assertThat(input.noteWeeks()).containsExactly(3);
        assertThat(input.hasNotes()).isTrue();
    }

    @Test
    void keepsNewestWeeksWhenNotesExceedTheBudgetAndReturnsThemOldestFirst() {
        String longNote = "가".repeat(300);
        SortedMap<Integer, SnapshotBody> bodies = new TreeMap<>();
        bodies.put(1, body(longNote, null, Map.of()));
        bodies.put(2, body(longNote, null, Map.of()));
        bodies.put(3, body(longNote, null, Map.of()));

        SynthesisInput input = new SynthesisInputAssembler(props(700)).assemble(List.of(), bodies);

        assertThat(input.notes()).extracting(SynthesisInput.NoteLine::week).containsExactly(2, 3);
    }

    @Test
    void skipsBlankNotesAndReportsNoNote() {
        SnapshotBody blank = body("   ", "EVENING", Map.of("toilet", ""));
        assertThat(SynthesisInputAssembler.noteLines(4, blank)).isEmpty();
        assertThat(SynthesisInputAssembler.hasNote(blank)).isFalse();
        assertThat(SynthesisInputAssembler.hasNote(null)).isFalse();
    }

    @Test
    void unknownTimeTagBecomesNullLabel() {
        List<SynthesisInput.NoteLine> lines = SynthesisInputAssembler.noteLines(2, body("합성 메모", "NOT_A_TAG", Map.of()));
        assertThat(lines).containsExactly(new SynthesisInput.NoteLine(2, null, null, "합성 메모"));
    }
}
```

주의: 이 Task 시점의 `QuestionCacheBody.Q`는 7개 인자다. Task 5에서 9개로 늘어나면 `template` 도우미를 `QuestionCacheBody.Q.template(...)` 호출로 바꾼다.

- [x] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew :api:test --tests "nextvisit.api.llm.SynthesisInputAssemblerTest"`
Expected: 컴파일 실패 — `SynthesisInputAssembler`가 없다.

- [x] **Step 3: 구현**

`SynthesisInput.java`:

```java
package nextvisit.api.llm;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 설계 3.1: LLM 정리 한 번의 입력. detections는 규칙 엔진 템플릿, notes는 주차별 보호자 원문. */
public record SynthesisInput(List<Detection> detections, List<NoteLine> notes) {

    public SynthesisInput {
        detections = List.copyOf(detections);
        notes = List.copyOf(notes);
    }

    public record Detection(String id, int rank, String sentence) {}

    /** itemLabel이 null이면 그 주의 자유 기록, 아니면 해당 항목의 메모다. */
    public record NoteLine(int week, String timeTagLabel, String itemLabel, String text) {}

    public Set<String> detectionIds() {
        Set<String> ids = new LinkedHashSet<>();
        detections.forEach(d -> ids.add(d.id()));
        return ids;
    }

    public Set<Integer> noteWeeks() {
        Set<Integer> weeks = new LinkedHashSet<>();
        notes.forEach(n -> weeks.add(n.week()));
        return weeks;
    }

    public boolean hasNotes() {
        return !notes.isEmpty();
    }
}
```

`SynthesisInputAssembler.java`:

```java
package nextvisit.api.llm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import nextvisit.api.questions.QuestionCacheBody;
import nextvisit.api.snapshots.SnapshotBody;
import nextvisit.engine.ObservationSet;
import nextvisit.engine.TimeTag;
import org.springframework.stereotype.Component;

/**
 * 설계 3.1: 관찰 변화와 주차별 보호자 원문을 LLM 입력으로 조립한다.
 * 원문이 상한을 넘으면 최근 주부터 주 단위로 넣고, 넣지 못한 주는 근거로 댈 수 없다.
 * 나중에 원문을 먼저 구조화하는 단계(설계 접근법 B)를 이 앞에 끼울 수 있게 순수하게 둔다.
 */
@Component
public class SynthesisInputAssembler {

    private final LlmProperties properties;

    public SynthesisInputAssembler(LlmProperties properties) {
        this.properties = properties;
    }

    public SynthesisInput assemble(List<QuestionCacheBody.Q> templates, SortedMap<Integer, SnapshotBody> bodiesByWeek) {
        List<SynthesisInput.Detection> detections = templates.stream()
            .map(q -> new SynthesisInput.Detection("D" + q.rank(), q.rank(), q.templateSentence()))
            .toList();

        int budget = properties.synthesisMaxNoteChars();
        List<SynthesisInput.NoteLine> picked = new ArrayList<>();
        List<Integer> newestFirst = new ArrayList<>(bodiesByWeek.keySet());
        newestFirst.sort(Comparator.reverseOrder());
        for (int week : newestFirst) {
            List<SynthesisInput.NoteLine> lines = noteLines(week, bodiesByWeek.get(week));
            int size = lines.stream().mapToInt(line -> line.text().length()).sum();
            if (size == 0) {
                continue;
            }
            if (size > budget) {
                break;
            }
            budget -= size;
            picked.addAll(lines);
        }
        picked.sort(Comparator.comparingInt(SynthesisInput.NoteLine::week));
        return new SynthesisInput(detections, picked);
    }

    public static List<SynthesisInput.NoteLine> noteLines(int week, SnapshotBody body) {
        List<SynthesisInput.NoteLine> lines = new ArrayList<>();
        if (body == null) {
            return lines;
        }
        if (body.freeNote() != null && hasText(body.freeNote().text())) {
            lines.add(new SynthesisInput.NoteLine(week, timeTagLabel(body.freeNote().timeTag()), null,
                body.freeNote().text().strip()));
        }
        if (body.items() != null) {
            for (Map.Entry<String, SnapshotBody.ItemValues> entry : body.items().entrySet()) {
                SnapshotBody.ItemValues values = entry.getValue();
                if (values != null && hasText(values.note())) {
                    lines.add(new SynthesisInput.NoteLine(week, null, itemLabel(entry.getKey()), values.note().strip()));
                }
            }
        }
        return lines;
    }

    public static boolean hasNote(SnapshotBody body) {
        return !noteLines(0, body).isEmpty();
    }

    private static boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private static String timeTagLabel(String tag) {
        if (tag == null) {
            return null;
        }
        try {
            return TimeTag.valueOf(tag).phrase();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String itemLabel(String code) {
        try {
            return ObservationSet.STROKE.item(code).label();
        } catch (RuntimeException e) {
            return code;
        }
    }
}
```

- [x] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew :api:test --tests "nextvisit.api.llm.SynthesisInputAssemblerTest"`
Expected: PASS (5 tests)

- [x] **Step 5: 인수인계 칸 갱신 후 커밋**

```bash
git add backend docs/superpowers/plans/2026-09-18-caregiver-question-synthesis.md
git commit -m "feat: assemble detections and weekly caregiver notes for question synthesis"
```

---
### Task 3: 정리 결과 검증기 (`SynthesisValidator`)

**Files:**
- Create: `backend/api/src/main/java/nextvisit/api/llm/SynthesisValidator.java`
- Test: `backend/api/src/test/java/nextvisit/api/llm/SynthesisValidatorTest.java`

**Interfaces:**
- Consumes: `SynthesisInput` (Task 2), `nextvisit.engine.Templates.containsForbiddenWord(String)`
- Produces:
  - `SynthesisValidator.Accepted validate(SynthesisInput input, String content)` — 실패하면 `SynthesisValidator.Rejected` 던짐
  - `record SynthesisValidator.Question(String sentence, List<String> detections, List<Integer> noteWeeks)` — sentence는 NFC 정규화·앞뒤 공백 제거된 값
  - `record SynthesisValidator.Accepted(List<Question> questions)`
  - `enum SynthesisValidator.Rule { JSON_OBJECT, ROOT_FIELDS, QUESTIONS_ARRAY, QUESTION_COUNT, QUESTION_FIELDS, SENTENCE_LENGTH, MARKDOWN, QUESTION_MARK, FORBIDDEN_WORD, DIRECTIVE, BASIS_EMPTY, UNKNOWN_DETECTION, UNKNOWN_NOTE_WEEK, UNSUPPORTED_NUMBER, DUPLICATE }`
  - `SynthesisValidator.Rejected.rule()`

- [x] **Step 1: 실패하는 테스트 작성**

모든 문장은 합성이다.

```java
package nextvisit.api.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;

class SynthesisValidatorTest {

    private final SynthesisValidator validator = new SynthesisValidator(new ObjectMapper());

    private final SynthesisInput input = new SynthesisInput(
        List.of(new SynthesisInput.Detection("D1", 1,
            "화장실은 혼자 가시게 바뀌셨는데 집 안에서 걷기는 6주째 그대로입니다. 걷기는 왜 안 늘고 있을까요?")),
        List.of(
            new SynthesisInput.NoteLine(3, "오후", null, "합성: 오후만 되면 오른쪽 어깨를 자꾸 만지신다"),
            new SynthesisInput.NoteLine(5, null, "화장실 이용", "합성: 밤에 두 번 깨서 화장실에 가셨다 7시쯤")));

    private static String one(String sentence, String detections, String weeks) {
        return "{\"questions\":[{\"sentence\":\"" + sentence + "\",\"detections\":" + detections
            + ",\"noteWeeks\":" + weeks + "}]}";
    }

    @Test
    void acceptsGroundedQuestionsAndNormalizesSentences() {
        String content = "{\"questions\":["
            + "{\"sentence\":\"  오후마다 오른쪽 어깨를 자꾸 만지시는데 어떤 점을 살펴보면 좋을까요?  \",\"detections\":[],\"noteWeeks\":[3]},"
            + "{\"sentence\":\"걷기가 6주째 그대로인데 집에서 어떻게 해야 할까요?\",\"detections\":[\"D1\"],\"noteWeeks\":[]}"
            + "]}";

        SynthesisValidator.Accepted accepted = validator.validate(input, content);

        assertThat(accepted.questions()).containsExactly(
            new SynthesisValidator.Question("오후마다 오른쪽 어깨를 자꾸 만지시는데 어떤 점을 살펴보면 좋을까요?", List.of(), List.of(3)),
            new SynthesisValidator.Question("걷기가 6주째 그대로인데 집에서 어떻게 해야 할까요?", List.of("D1"), List.of()));
    }

    @Test
    void allowsTheCitedWeekNumberAndNumbersFromCitedNotes() {
        String content = one("5주에 적은 것처럼 밤 7시쯤 화장실에 가시는데 괜찮을까요?", "[]", "[5]");
        assertThat(validator.validate(input, content).questions()).hasSize(1);
    }

    static Stream<Arguments> rejected() {
        String ok = "오후마다 어깨를 자꾸 만지시는데 어떤 점을 보면 좋을까요?";
        return Stream.of(
            Arguments.of("not json", SynthesisValidator.Rule.JSON_OBJECT),
            Arguments.of("[1]", SynthesisValidator.Rule.JSON_OBJECT),
            Arguments.of("{\"questions\":[],\"note\":1}", SynthesisValidator.Rule.ROOT_FIELDS),
            Arguments.of("{\"questions\":{}}", SynthesisValidator.Rule.QUESTIONS_ARRAY),
            Arguments.of("{\"questions\":[]}", SynthesisValidator.Rule.QUESTION_COUNT),
            Arguments.of("{\"questions\":[" + String.join(",", List.of(
                "{\"sentence\":\"" + ok + "\",\"detections\":[],\"noteWeeks\":[3]}",
                "{\"sentence\":\"" + ok + " 하나\",\"detections\":[],\"noteWeeks\":[3]}",
                "{\"sentence\":\"" + ok + " 둘\",\"detections\":[],\"noteWeeks\":[3]}",
                "{\"sentence\":\"" + ok + " 셋\",\"detections\":[],\"noteWeeks\":[3]}")) + "]}",
                SynthesisValidator.Rule.QUESTION_COUNT),
            Arguments.of("{\"questions\":[{\"sentence\":\"" + ok + "\",\"noteWeeks\":[3]}]}", SynthesisValidator.Rule.QUESTION_FIELDS),
            Arguments.of("{\"questions\":[{\"sentence\":\"" + ok + "\",\"detections\":\"D1\",\"noteWeeks\":[3]}]}", SynthesisValidator.Rule.QUESTION_FIELDS),
            Arguments.of("{\"questions\":[{\"sentence\":\"" + ok + "\",\"detections\":[],\"noteWeeks\":[\"3\"]}]}", SynthesisValidator.Rule.QUESTION_FIELDS),
            Arguments.of(one("괜찮을까요?", "[]", "[3]"), SynthesisValidator.Rule.SENTENCE_LENGTH),
            Arguments.of(one("가".repeat(158) + "까요?", "[]", "[3]"), SynthesisValidator.Rule.SENTENCE_LENGTH),
            Arguments.of(one("**오후마다** 어깨를 만지시는데 괜찮을까요?", "[]", "[3]"), SynthesisValidator.Rule.MARKDOWN),
            Arguments.of(one("오후마다 어깨를 자꾸 만지시는 것을 말씀드립니다.", "[]", "[3]"), SynthesisValidator.Rule.QUESTION_MARK),
            Arguments.of(one("운동을 더 하면 어깨가 괜찮아질까요?", "[]", "[3]"), SynthesisValidator.Rule.FORBIDDEN_WORD),
            Arguments.of(one("밤에 깨시면 같이 가면 돼요 그렇게 하면 될까요?", "[]", "[5]"), SynthesisValidator.Rule.DIRECTIVE),
            Arguments.of(one(ok, "[]", "[]"), SynthesisValidator.Rule.BASIS_EMPTY),
            Arguments.of(one(ok, "[\"D9\"]", "[]"), SynthesisValidator.Rule.UNKNOWN_DETECTION),
            Arguments.of(one(ok, "[]", "[2]"), SynthesisValidator.Rule.UNKNOWN_NOTE_WEEK),
            Arguments.of(one("4주 중 3주나 어깨를 만지셨는데 괜찮을까요?", "[]", "[3]"), SynthesisValidator.Rule.UNSUPPORTED_NUMBER),
            Arguments.of(one("걷기가 8주째 그대로인데 괜찮을까요?", "[\"D1\"]", "[]"), SynthesisValidator.Rule.UNSUPPORTED_NUMBER),
            Arguments.of("{\"questions\":["
                + "{\"sentence\":\"" + ok + "\",\"detections\":[],\"noteWeeks\":[3]},"
                + "{\"sentence\":\" " + ok + "\",\"detections\":[\"D1\"],\"noteWeeks\":[]}]}",
                SynthesisValidator.Rule.DUPLICATE));
    }

    @ParameterizedTest
    @MethodSource("rejected")
    void rejectsWithTheStableRule(String content, SynthesisValidator.Rule rule) {
        assertThatThrownBy(() -> validator.validate(input, content))
            .isInstanceOfSatisfying(SynthesisValidator.Rejected.class, e -> assertThat(e.rule()).isEqualTo(rule));
    }

    @Test
    void rejectsDuplicateJsonKeysAndTrailingTokens() {
        assertThatThrownBy(() -> validator.validate(input,
            "{\"questions\":[],\"questions\":[]}"))
            .isInstanceOfSatisfying(SynthesisValidator.Rejected.class,
                e -> assertThat(e.rule()).isEqualTo(SynthesisValidator.Rule.JSON_OBJECT));
        assertThatThrownBy(() -> validator.validate(input,
            one("오후마다 어깨를 자꾸 만지시는데 어떤 점을 보면 좋을까요?", "[]", "[3]") + " trailing"))
            .isInstanceOfSatisfying(SynthesisValidator.Rejected.class,
                e -> assertThat(e.rule()).isEqualTo(SynthesisValidator.Rule.JSON_OBJECT));
    }

    @Test
    void rejectedCarriesNoSentenceText() {
        assertThatThrownBy(() -> validator.validate(input, one("운동을 더 하면 어깨가 괜찮아질까요?", "[]", "[3]")))
            .hasMessage("FORBIDDEN_WORD");
    }
}
```

`pom`이 아니라 Gradle이다. `junit-jupiter-params`가 없으면 `backend/api/build.gradle.kts`의 `dependencies`에 `testImplementation("org.junit.jupiter:junit-jupiter-params")`를 추가한다(Spring Boot BOM이 버전을 정한다). 먼저 `grep -rn "ParameterizedTest" backend/api/src/test | head -1`로 이미 쓰는지 확인한다.

- [x] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew :api:test --tests "nextvisit.api.llm.SynthesisValidatorTest"`
Expected: 컴파일 실패 — `SynthesisValidator`가 없다.

- [x] **Step 3: 구현**

```java
package nextvisit.api.llm;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import nextvisit.engine.Templates;
import org.springframework.stereotype.Component;

/**
 * 설계 3.3: LLM 정리 결과를 받아들일지 정한다. 하나라도 어기면 그 시도는 실패다.
 * 예외 메시지에는 규칙 이름만 담는다 — 문장과 원문을 로그로 흘리지 않기 위해서다.
 */
@Component
public class SynthesisValidator {

    static final int MAX_QUESTIONS = 3;
    static final int MIN_LENGTH = 10;
    static final int MAX_LENGTH = 160;

    private static final Set<String> ROOT_FIELDS = Set.of("questions");
    private static final Set<String> QUESTION_FIELDS = Set.of("sentence", "detections", "noteWeeks");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern MARKDOWN = Pattern.compile(
        "[\\r\\n\\\\`*_~#<>\\[\\]|]|^\\s*(?:>|[-+=]|\\d{1,9}[.)](?:\\s|$))");
    private static final Pattern INTERROGATIVE_ENDING = Pattern.compile(
        "(?:나요|까요|가요|습니까|지요|죠)\\?$");
    /** 옛 QuestionOutputGuard의 지시형 패턴에서 '해야'만 뺐다. "어떻게 해야 할까요?"는 보호자의 질문이다. */
    private static final Pattern DIRECTIVE = Pattern.compile(
        "(?:세요|십시오|해\\s*주세요|기\\s*바랍니다|"
            + "해\\s*(?:봐요|볼까요)|면\\s*(?:됩니다|돼요)|[가-힣]+라)(?=\\s|[,.!?]|$)");

    private final ObjectMapper mapper;

    public SynthesisValidator(ObjectMapper mapper) {
        this.mapper = mapper.copy()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public Accepted validate(SynthesisInput input, String content) {
        JsonNode root;
        try {
            root = mapper.readTree(content);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new Rejected(Rule.JSON_OBJECT);
        }
        if (root == null || !root.isObject()) {
            throw new Rejected(Rule.JSON_OBJECT);
        }
        if (!fieldNames(root).equals(ROOT_FIELDS)) {
            throw new Rejected(Rule.ROOT_FIELDS);
        }
        JsonNode questions = root.get("questions");
        if (!questions.isArray()) {
            throw new Rejected(Rule.QUESTIONS_ARRAY);
        }
        if (questions.isEmpty() || questions.size() > MAX_QUESTIONS) {
            throw new Rejected(Rule.QUESTION_COUNT);
        }
        List<Question> accepted = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode node : questions) {
            Question question = parse(node);
            check(input, question);
            if (!seen.add(question.sentence())) {
                throw new Rejected(Rule.DUPLICATE);
            }
            accepted.add(question);
        }
        return new Accepted(List.copyOf(accepted));
    }

    private static Question parse(JsonNode node) {
        if (!node.isObject() || !fieldNames(node).equals(QUESTION_FIELDS)) {
            throw new Rejected(Rule.QUESTION_FIELDS);
        }
        JsonNode sentence = node.get("sentence");
        JsonNode detections = node.get("detections");
        JsonNode weeks = node.get("noteWeeks");
        if (!sentence.isTextual() || !detections.isArray() || !weeks.isArray()) {
            throw new Rejected(Rule.QUESTION_FIELDS);
        }
        List<String> ids = new ArrayList<>();
        for (JsonNode id : detections) {
            if (!id.isTextual()) {
                throw new Rejected(Rule.QUESTION_FIELDS);
            }
            ids.add(id.textValue());
        }
        List<Integer> noteWeeks = new ArrayList<>();
        for (JsonNode week : weeks) {
            if (!week.isIntegralNumber() || !week.canConvertToInt()) {
                throw new Rejected(Rule.QUESTION_FIELDS);
            }
            noteWeeks.add(week.intValue());
        }
        String normalized = Normalizer.normalize(sentence.textValue(), Normalizer.Form.NFC).strip();
        return new Question(normalized, List.copyOf(ids), List.copyOf(noteWeeks));
    }

    private static void check(SynthesisInput input, Question q) {
        String s = q.sentence();
        if (s.length() < MIN_LENGTH || s.length() > MAX_LENGTH) {
            throw new Rejected(Rule.SENTENCE_LENGTH);
        }
        if (MARKDOWN.matcher(s).find()) {
            throw new Rejected(Rule.MARKDOWN);
        }
        if (!INTERROGATIVE_ENDING.matcher(s).find()) {
            throw new Rejected(Rule.QUESTION_MARK);
        }
        if (Templates.containsForbiddenWord(s)) {
            throw new Rejected(Rule.FORBIDDEN_WORD);
        }
        if (DIRECTIVE.matcher(s).find()) {
            throw new Rejected(Rule.DIRECTIVE);
        }
        if (q.detections().isEmpty() && q.noteWeeks().isEmpty()) {
            throw new Rejected(Rule.BASIS_EMPTY);
        }
        if (!input.detectionIds().containsAll(q.detections())) {
            throw new Rejected(Rule.UNKNOWN_DETECTION);
        }
        if (!input.noteWeeks().containsAll(q.noteWeeks())) {
            throw new Rejected(Rule.UNKNOWN_NOTE_WEEK);
        }
        if (!numbersSupported(input, q)) {
            throw new Rejected(Rule.UNSUPPORTED_NUMBER);
        }
    }

    private static boolean numbersSupported(SynthesisInput input, Question q) {
        Set<String> allowed = new HashSet<>();
        for (SynthesisInput.Detection d : input.detections()) {
            if (q.detections().contains(d.id())) {
                collectNumbers(d.sentence(), allowed);
            }
        }
        for (SynthesisInput.NoteLine n : input.notes()) {
            if (q.noteWeeks().contains(n.week())) {
                collectNumbers(n.text(), allowed);
            }
        }
        q.noteWeeks().forEach(week -> allowed.add(Integer.toString(week)));
        Matcher m = NUMBER.matcher(q.sentence());
        while (m.find()) {
            if (!allowed.contains(canonical(m.group()))) {
                return false;
            }
        }
        return true;
    }

    private static void collectNumbers(String text, Set<String> into) {
        Matcher m = NUMBER.matcher(Normalizer.normalize(text, Normalizer.Form.NFC));
        while (m.find()) {
            into.add(canonical(m.group()));
        }
    }

    private static String canonical(String digits) {
        return new BigInteger(digits).toString();
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        Iterator<String> it = node.fieldNames();
        it.forEachRemaining(names::add);
        return names;
    }

    public record Question(String sentence, List<String> detections, List<Integer> noteWeeks) {}

    public record Accepted(List<Question> questions) {}

    public enum Rule {
        JSON_OBJECT,
        ROOT_FIELDS,
        QUESTIONS_ARRAY,
        QUESTION_COUNT,
        QUESTION_FIELDS,
        SENTENCE_LENGTH,
        MARKDOWN,
        QUESTION_MARK,
        FORBIDDEN_WORD,
        DIRECTIVE,
        BASIS_EMPTY,
        UNKNOWN_DETECTION,
        UNKNOWN_NOTE_WEEK,
        UNSUPPORTED_NUMBER,
        DUPLICATE
    }

    public static final class Rejected extends RuntimeException {
        private final Rule rule;

        public Rejected(Rule rule) {
            super(rule.name(), null, false, false);
            this.rule = rule;
        }

        public Rule rule() {
            return rule;
        }
    }
}
```

- [x] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew :api:test --tests "nextvisit.api.llm.SynthesisValidatorTest"`
Expected: PASS. 어떤 거부 사례가 다른 규칙으로 걸리면, 규칙 순서(길이 → 마크다운 → 의문형 → 금지어 → 지시형 → 근거 → 숫자 → 중복)를 바꾸지 말고 **테스트 문장**을 그 규칙만 어기도록 고친다.

- [x] **Step 5: 인수인계 칸 갱신 후 커밋**

```bash
git add backend docs/superpowers/plans/2026-09-18-caregiver-question-synthesis.md
git commit -m "feat: validate synthesized questions for shape, grounding, numbers and forbidden wording"
```

---

### Task 4: 정리 지시문 (`QuestionSynthesisPrompt`)

**Files:**
- Create: `backend/api/src/main/java/nextvisit/api/llm/QuestionSynthesisPrompt.java`
- Test: `backend/api/src/test/java/nextvisit/api/llm/QuestionSynthesisPromptTest.java`

**Interfaces:**
- Consumes: `LlmPrompt` (Task 1), `SynthesisInput` (Task 2)
- Produces: `LlmPrompt QuestionSynthesisPrompt.build(SynthesisInput input, Optional<String> retryRule)`; user 메시지는 JSON `{"detections":[{"id","sentence"}],"notes":[{"week","timeTag"?,"item"?,"text"}]}` (null 필드는 생략)

- [x] **Step 1: 실패하는 테스트 작성**

```java
package nextvisit.api.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QuestionSynthesisPromptTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final QuestionSynthesisPrompt prompts = new QuestionSynthesisPrompt(mapper);
    private final SynthesisInput input = new SynthesisInput(
        List.of(new SynthesisInput.Detection("D1", 1, "합성 변화 문장입니다. 어떻게 보시나요?")),
        List.of(
            new SynthesisInput.NoteLine(3, "오후", null, "합성 자유 기록"),
            new SynthesisInput.NoteLine(4, null, "화장실 이용", "합성 항목 메모")));

    @Test
    void userMessageCarriesDetectionsAndNotesAsJsonWithoutNullFields() throws Exception {
        JsonNode user = mapper.readTree(prompts.build(input, Optional.empty()).userMessage());

        assertThat(user.at("/detections/0/id").asText()).isEqualTo("D1");
        assertThat(user.at("/detections/0/sentence").asText()).isEqualTo("합성 변화 문장입니다. 어떻게 보시나요?");
        assertThat(user.at("/notes/0/week").asInt()).isEqualTo(3);
        assertThat(user.at("/notes/0/timeTag").asText()).isEqualTo("오후");
        assertThat(user.at("/notes/0").has("item")).isFalse();
        assertThat(user.at("/notes/1/item").asText()).isEqualTo("화장실 이용");
        assertThat(user.at("/notes/1").has("timeTag")).isFalse();
        assertThat(user.at("/notes/1/text").asText()).isEqualTo("합성 항목 메모");
    }

    @Test
    void systemMessageStatesTheContract() {
        String system = prompts.build(input, Optional.empty()).systemMessage();

        assertThat(system)
            .contains("선생님")
            .contains("입력에 있는 사실만")
            .contains("'~까요?', '~나요?', '~가요?'")
            .contains("1개에서 3개")
            .contains("둘 중 적어도 하나는 비우지 마세요")
            .contains("{\"questions\":[{\"sentence\":\"질문?\",\"detections\":[\"D1\"],\"noteWeeks\":[3]}]}")
            .doesNotContain("/no_think");
    }

    @Test
    void retryNamesTheViolatedRule() {
        String system = prompts.build(input, Optional.of("UNSUPPORTED_NUMBER")).systemMessage();
        assertThat(system).endsWith("직전 응답은 검증 규칙 UNSUPPORTED_NUMBER을 위반했습니다. 이 규칙을 지켜 다시 만드세요.");
    }
}
```

- [x] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew :api:test --tests "nextvisit.api.llm.QuestionSynthesisPromptTest"`
Expected: 컴파일 실패 — 클래스가 없다.

- [x] **Step 3: 구현**

```java
package nextvisit.api.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 설계 3.2: 보호자 원문과 관찰 변화를 모아 보호자가 선생님께 여쭤볼 질문을 정리하게 한다.
 * 금지어 목록에 "치료"가 있어 "치료사"라는 단어가 거부되므로 호칭은 "선생님"으로 고정한다.
 */
@Component
public class QuestionSynthesisPrompt {

    static final String SYSTEM = """
        당신은 뇌졸중 후 집에서 지내는 환자의 보호자를 돕습니다.
        보호자가 다음 진료에서 선생님께 직접 여쭤볼 질문을 정리합니다.
        입력의 detections는 관찰 기록에서 규칙으로 찾은 변화이고, notes는 보호자가 주마다 직접 적은 기록입니다.
        보호자가 적은 걱정과 궁금증을 먼저 살리고, 관련된 관찰 변화가 있으면 한 질문 안에 함께 엮으세요.
        입력에 있는 사실만 쓰세요. 입력에 없는 숫자, 기간, 증상, 원인을 만들지 마세요.
        진단하거나 원인을 단정하지 마세요. 점수를 매기지 마세요. 무엇을 하라고 권하지 마세요. 상태가 나아졌는지 판단하지 마세요.
        문장에 '치료', '재활', '운동', '낙상', '진단', '점수', '처방', '회복', '개선', '악화', '호전', '정상', '위험'이라는 말을 쓰지 마세요. 상대는 '선생님'이라고 부르세요.
        각 질문은 보호자가 선생님께 드리는 존댓말 한 문장이고 '~까요?', '~나요?', '~가요?' 중 하나로 끝납니다. 지시하는 말투는 쓰지 마세요.
        질문은 1개에서 3개까지이고 서로 겹치지 않게 하세요.
        각 질문에 근거를 적으세요. detections에는 참고한 변화의 id를, noteWeeks에는 참고한 보호자 기록의 week를 넣고, 둘 중 적어도 하나는 비우지 마세요.
        마크다운이나 설명 없이 정확히 {"questions":[{"sentence":"질문?","detections":["D1"],"noteWeeks":[3]}]} 형태의 JSON 객체 하나만 반환하세요.""";

    private final ObjectMapper mapper;

    public QuestionSynthesisPrompt(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public LlmPrompt build(SynthesisInput input, Optional<String> retryRule) {
        Payload payload = new Payload(
            input.detections().stream().map(d -> new DetectionPayload(d.id(), d.sentence())).toList(),
            input.notes().stream().map(n -> new NotePayload(n.week(), n.timeTagLabel(), n.itemLabel(), n.text())).toList());
        String system = retryRule
            .map(rule -> SYSTEM + "\n직전 응답은 검증 규칙 " + rule + "을 위반했습니다. 이 규칙을 지켜 다시 만드세요.")
            .orElse(SYSTEM);
        return new LlmPrompt(system, json(payload));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("synthesis prompt serialization failed", e);
        }
    }

    record Payload(List<DetectionPayload> detections, List<NotePayload> notes) {}

    record DetectionPayload(String id, String sentence) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record NotePayload(int week, String timeTag, String item, String text) {}
}
```

- [x] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew :api:test --tests "nextvisit.api.llm.QuestionSynthesisPromptTest"`
Expected: PASS (3 tests)

- [x] **Step 5: 인수인계 칸 갱신 후 커밋**

```bash
git add backend docs/superpowers/plans/2026-09-18-caregiver-question-synthesis.md
git commit -m "feat: prompt the LLM to organize caregiver notes into grounded visit questions"
```

---

### Task 5: 정리 경로 연결 — 캐시 출처·근거, 호출 조건, 조정자 교체

**Files:**
- Modify: `backend/api/src/main/java/nextvisit/api/questions/QuestionCacheBody.java`
- Modify: `backend/api/src/main/java/nextvisit/api/questions/QuestionService.java`
- Modify: `backend/api/src/main/java/nextvisit/api/llm/QuestionGenerationCoordinator.java`
- Delete: `backend/api/src/main/java/nextvisit/api/llm/QuestionRewritePrompt.java`, `QuestionOutputGuard.java`
- Delete: `backend/api/src/test/java/nextvisit/api/llm/QuestionRewritePromptTest.java`, `QuestionOutputGuardTest.java`
- Modify (호출부): `QuestionCacheBody.Q(` 생성자를 쓰는 모든 파일 — `grep -rn "QuestionCacheBody.Q(" backend/api/src`
- Test: `backend/api/src/test/java/nextvisit/api/llm/QuestionGenerationCoordinatorTest.java`, `QuestionAsyncIntegrationTest.java`, `backend/api/src/test/java/nextvisit/api/questions/QuestionServiceTest.java`

**Interfaces:**
- Consumes: Task 2~4의 `SynthesisInputAssembler`, `QuestionSynthesisPrompt`, `SynthesisValidator`
- Produces:
  - `QuestionCacheBody.Q(int rank, String type, List<String> items, SignalRef signal, String templateSentence, String sentence, String source, String origin, Basis basis)`
  - `static Q QuestionCacheBody.Q.template(int rank, String type, List<String> items, SignalRef signal, String sentence)` — origin `TEMPLATE`, basis `(["D"+rank], [])`
  - `String Q.originOrDefault()`, `Basis Q.basisOrEmpty()`
  - `record QuestionCacheBody.Basis(List<String> detections, List<Integer> noteWeeks)` + `Basis.EMPTY`
  - 상수 `ORIGIN_TEMPLATE="TEMPLATE"`, `ORIGIN_LLM="LLM"`, `ORIGIN_CAREGIVER="CAREGIVER"`, `TYPE_SYNTHESIS="SYNTHESIS"`
  - `static QuestionCacheBody QuestionGenerationCoordinator.toBody(QuestionCacheBody templates, SynthesisValidator.Accepted accepted)`
  - 실패 코드 `NO_NOTES`(원문이 예산 안에 하나도 없을 때)

- [x] **Step 1: 실패하는 테스트 작성**

`QuestionGenerationCoordinatorTest`를 새 생성자에 맞춰 다시 쓴다. 기존 파일의 import·`@Mock`·`OutputCaptureExtension` 구성은 그대로 두고, `setUp`과 테스트를 아래로 바꾼다.

```java
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
                new SnapshotBody.FreeNote("합성: 오후마다 어깨를 만지심", "AFTERNOON")))));
        when(writer.markDone(any(), any(), any())).thenReturn(true);
        when(writer.markFailed(any(), any())).thenReturn(true);
        LlmProperties properties = new LlmProperties(true, URI.create("http://localhost:11434/v1"),
            "qwen3:4b-q4_K_M", "ollama", "", "", Duration.ofSeconds(3), Duration.ofSeconds(45), 3, 3000, "none", 4000);
        coordinator = new QuestionGenerationCoordinator(caches, snapshots, json, client,
            new SynthesisInputAssembler(properties), new QuestionSynthesisPrompt(mapper),
            new SynthesisValidator(mapper), writer, properties);
    }

    private Snapshot snapshot(int week, SnapshotBody body) {
        Snapshot s = mock(Snapshot.class);
        when(s.getWeek()).thenReturn(week);
        when(s.getBody()).thenReturn(json.toJson(body));
        return s;
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
    void mapsClientFailuresToTheirCode(CapturedOutput output) {
        when(client.complete(any())).thenThrow(new LlmClientException(LlmFailureCode.TIMEOUT));

        coordinator.generate(new QuestionGenerationRequested(caseId, generationId));

        verify(client, times(3)).complete(any());
        assertThat(output).contains("code=TIMEOUT");
    }
```

`LlmClientException` 생성자 모양은 기존 파일을 따른다. 기존 테스트 중 위와 겹치지 않는 것(예: 재시도 중 세대가 바뀌면 멈춤)은 새 생성자로 옮겨 남긴다.

`QuestionServiceTest`에 호출 조건 테스트를 추가한다. 기존 테스트의 케이스 준비 방식을 그대로 쓰되, LLM이 켜진 설정에서:

```java
    @Test
    void schedulesSynthesisOnlyWhenACaregiverNoteExists() {
        // 원문이 없는 케이스: 템플릿만 두고 READY
        // 원문이 있는 케이스: LLM_PENDING, QuestionGenerationRequested 이벤트 1회
    }
```

이 테스트는 기존 `QuestionServiceTest`의 LLM 켜짐/꺼짐 테스트와 같은 구성(`@MockitoBean` 이벤트 발행 확인 또는 `ApplicationEvents`)을 복사해 쓴다. 기존 파일에 "질문이 비어 있으면 LLM을 부르지 않는다" 류의 테스트가 있으면 "원문이 없으면 부르지 않는다"로 뜻을 바꾼다.

`QuestionAsyncIntegrationTest`: `successfulRewrite(QuestionRewritePrompt.Prompt ...)` 도우미를 지우고, 목 `LlmClient`가 위 `GROUNDED`와 같은 형태를 돌려주게 바꾼다. 준비 케이스는 `DemoSeedWriter`로 만드는데 시드에 자유 기록이 있으므로(`DemoSeedWriter.java`의 합성 메모) `noteWeeks`에는 **시드에 실제로 원문이 있는 주**를 넣는다. `grep -n "FreeNote" backend/api/src/main/java/nextvisit/api/demo/DemoSeedWriter.java`로 주차를 확인한다. 기대값은 캐시 `LLM_DONE`, 첫 질문 `origin=LLM`.

- [x] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew :api:test --tests "nextvisit.api.llm.*" --tests "nextvisit.api.questions.QuestionServiceTest"`
Expected: 컴파일 실패 — 새 생성자와 `Q.template`, `Basis`가 없다.

- [x] **Step 3: 구현**

`QuestionCacheBody.java` 전체:

```java
package nextvisit.api.questions;

import java.util.List;

/**
 * 설계 2.3 + 2026-09-17 정리 설계 4.1. sentence는 화면용, templateSentence는 폴백 기준.
 * origin·basis는 2026-09-17에 추가돼 그 전 캐시에는 없다 — originOrDefault/basisOrEmpty로 읽는다.
 */
public record QuestionCacheBody(List<Q> questions, int engineDetectionCount) {

    public static final String SOURCE_TEMPLATE = "TEMPLATE";
    public static final String SOURCE_LLM = "LLM";
    public static final String ORIGIN_TEMPLATE = "TEMPLATE";
    public static final String ORIGIN_LLM = "LLM";
    public static final String ORIGIN_CAREGIVER = "CAREGIVER";
    public static final String TYPE_SYNTHESIS = "SYNTHESIS";

    public record Q(int rank, String type, List<String> items, SignalRef signal,
                    String templateSentence, String sentence, String source,
                    String origin, Basis basis) {

        public static Q template(int rank, String type, List<String> items, SignalRef signal, String sentence) {
            return new Q(rank, type, items, signal, sentence, sentence, SOURCE_TEMPLATE, ORIGIN_TEMPLATE,
                new Basis(List.of("D" + rank), List.of()));
        }

        public String originOrDefault() {
            if (origin != null) {
                return origin;
            }
            return SOURCE_LLM.equals(source) ? ORIGIN_LLM : ORIGIN_TEMPLATE;
        }

        public Basis basisOrEmpty() {
            return basis != null ? basis : Basis.EMPTY;
        }
    }

    public record Basis(List<String> detections, List<Integer> noteWeeks) {
        public static final Basis EMPTY = new Basis(List.of(), List.of());
    }

    public record SignalRef(String action, String kind) {}
}
```

`QuestionService.refresh`:

```java
            qs.add(QuestionCacheBody.Q.template(i + 1, d.type().name(), d.items(), ref, template));
```

```java
        // 2026-09-17 정리 설계 3.1: 보호자 원문이 하나도 없으면 LLM을 부르지 않는다.
        boolean generateWithLlm = properties.enabled() && snaps.stream().anyMatch(this::hasNote);
```

```java
    private boolean hasNote(Snapshot snapshot) {
        return SynthesisInputAssembler.hasNote(json.fromJson(snapshot.getBody(), SnapshotBody.class));
    }
```

(`import nextvisit.api.llm.SynthesisInputAssembler;`, `import nextvisit.api.snapshots.SnapshotBody;`)

`QuestionGenerationCoordinator.java`에서 필드·생성자·`generate`의 시도 반복부·`rewriteAll`을 바꾼다.

```java
    private final QuestionCacheRepository caches;
    private final SnapshotRepository snapshots;
    private final Json json;
    private final LlmClient client;
    private final SynthesisInputAssembler assembler;
    private final QuestionSynthesisPrompt prompts;
    private final SynthesisValidator validator;
    private final QuestionGenerationResultWriter writer;
    private final LlmProperties properties;

    public QuestionGenerationCoordinator(QuestionCacheRepository caches, SnapshotRepository snapshots, Json json,
                                         LlmClient client, SynthesisInputAssembler assembler,
                                         QuestionSynthesisPrompt prompts, SynthesisValidator validator,
                                         QuestionGenerationResultWriter writer, LlmProperties properties) {
        this.caches = caches;
        this.snapshots = snapshots;
        this.json = json;
        this.client = client;
        this.assembler = assembler;
        this.prompts = prompts;
        this.validator = validator;
        this.writer = writer;
        this.properties = properties;
    }
```

`templates`를 읽은 직후:

```java
        SynthesisInput input;
        try {
            SortedMap<Integer, SnapshotBody> bodies = new TreeMap<>();
            for (Snapshot snapshot : snapshots.findByCaseIdOrderByWeekAsc(request.caseId())) {
                bodies.put(snapshot.getWeek(), json.fromJson(snapshot.getBody(), SnapshotBody.class));
            }
            input = assembler.assemble(templates.questions(), bodies);
        } catch (RuntimeException e) {
            finishFailed(request, started, 0, "SNAPSHOT_READ_FAILED");
            return;
        }
        if (!input.hasNotes()) {
            finishFailed(request, started, 0, "NO_NOTES");
            return;
        }
```

반복문 안의 try 블록:

```java
            try {
                LlmPrompt prompt = prompts.build(input, Optional.ofNullable(retryRule));
                String content = client.complete(prompt);
                SynthesisValidator.Accepted accepted = validator.validate(input, content);
                QuestionCacheBody synthesized = toBody(templates, accepted);
                if (writer.markDone(request.caseId(), request.generationId(), synthesized)) {
                    log.info("llm generation result generationId={} model={} attempts={} elapsedMs={} code=SUCCESS",
                        request.generationId(), properties.model(), attempts, elapsedMillis(started));
                } else {
                    log.debug("llm generation result generationId={} model={} attempts={} elapsedMs={} code=STALE_RESULT",
                        request.generationId(), properties.model(), attempts, elapsedMillis(started));
                }
                return;
            } catch (SynthesisValidator.Rejected e) {
                retryRule = e.rule().name();
                lastCode = retryRule;
            } catch (LlmClientException e) {
                retryRule = null;
                lastCode = e.code().name();
            } catch (RuntimeException e) {
                retryRule = null;
                lastCode = "INTERNAL_ERROR";
            }
```

`rewriteAll`을 지우고:

```java
    /** 검증을 통과한 질문을 캐시 형태로 바꾼다. 근거 변화의 항목·신호를 이어받아 기존 근거 표를 재사용한다. */
    static QuestionCacheBody toBody(QuestionCacheBody templates, SynthesisValidator.Accepted accepted) {
        Map<String, QuestionCacheBody.Q> byId = new HashMap<>();
        for (QuestionCacheBody.Q template : templates.questions()) {
            byId.put("D" + template.rank(), template);
        }
        List<QuestionCacheBody.Q> out = new ArrayList<>();
        int rank = 1;
        for (SynthesisValidator.Question q : accepted.questions()) {
            Set<String> items = new LinkedHashSet<>();
            QuestionCacheBody.SignalRef signal = null;
            for (String id : q.detections()) {
                QuestionCacheBody.Q template = byId.get(id);
                items.addAll(template.items());
                if (signal == null) {
                    signal = template.signal();
                }
            }
            out.add(new QuestionCacheBody.Q(rank++, QuestionCacheBody.TYPE_SYNTHESIS, List.copyOf(items), signal,
                q.sentence(), q.sentence(), QuestionCacheBody.SOURCE_LLM, QuestionCacheBody.ORIGIN_LLM,
                new QuestionCacheBody.Basis(q.detections(), q.noteWeeks())));
        }
        return new QuestionCacheBody(List.copyOf(out), templates.engineDetectionCount());
    }
```

필요한 import: `java.util.ArrayList`, `LinkedHashSet`, `Set`, `SortedMap`, `TreeMap`, `nextvisit.api.snapshots.Snapshot`, `SnapshotBody`, `SnapshotRepository`. 쓰지 않게 된 import는 지운다.

`QuestionRewritePrompt.java`, `QuestionOutputGuard.java`와 두 테스트 파일을 지운다:

```bash
git rm backend/api/src/main/java/nextvisit/api/llm/QuestionRewritePrompt.java \
       backend/api/src/main/java/nextvisit/api/llm/QuestionOutputGuard.java \
       backend/api/src/test/java/nextvisit/api/llm/QuestionRewritePromptTest.java \
       backend/api/src/test/java/nextvisit/api/llm/QuestionOutputGuardTest.java
```

남은 `new QuestionCacheBody.Q(` 호출(`grep -rn "QuestionCacheBody.Q(" backend/api/src`)은 템플릿이면 `Q.template(...)`로, 아니면 끝에 `origin, basis` 두 인자를 붙인다. Task 2의 `SynthesisInputAssemblerTest.template` 도우미도 `Q.template(rank, "STALL", List.of("ambulation"), null, sentence)`로 바꾼다.

- [x] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew clean test`
Expected: 전체 PASS. 실패한 테스트가 옛 `SURFACE_REWRITE`·`RANK_SET`·연결 규칙을 검사하던 것이면, 그 테스트는 이 설계가 없앤 동작이므로 지우고 커밋 메시지 본문에 지운 테스트 이름을 적는다. 그 밖의 실패는 고친다.

- [x] **Step 5: 인수인계 칸 갱신 후 커밋**

```bash
git add -A backend docs/superpowers/plans/2026-09-18-caregiver-question-synthesis.md
git commit -m "feat: synthesize visit questions from caregiver notes instead of rewriting templates"
```

---

### Task 6: 보호자 확정 목록과 진료 준비 API

**Files:**
- Create: `backend/api/src/main/resources/db/migration/postgresql/V3__confirmed_questions.sql`
- Create: `backend/api/src/main/resources/db/migration/h2/V3__confirmed_questions.sql`
- Create: `backend/api/src/main/java/nextvisit/api/questions/ConfirmedItem.java`
- Create: `backend/api/src/main/java/nextvisit/api/questions/QuestionListService.java`
- Create: `backend/api/src/main/java/nextvisit/api/questions/SaveQuestionsRequest.java`
- Modify: `backend/api/src/main/java/nextvisit/api/cases/CaseEntity.java`
- Modify: `backend/api/src/main/java/nextvisit/api/questions/PrepCardDto.java`
- Modify: `backend/api/src/main/java/nextvisit/api/questions/PrepCardService.java`
- Modify: `backend/api/src/main/java/nextvisit/api/questions/PrepCardController.java`
- Test: `backend/api/src/test/java/nextvisit/api/questions/PrepCardControllerTest.java`

**Interfaces:**
- Consumes: `QuestionCacheBody.Q.originOrDefault()/basisOrEmpty()`, `SynthesisInputAssembler.noteLines(...)` (Task 2, 5)
- Produces:
  - `record ConfirmedItem(String id, String sentence, String origin, boolean edited, String type, List<String> items, QuestionCacheBody.SignalRef signal, QuestionCacheBody.Basis basis)` + `static ConfirmedItem caregiver(String id, String sentence)`
  - `QuestionListService.visible(CaseEntity, QuestionCacheBody)` → `List<ConfirmedItem>`; 캐시 항목 id는 `"q" + rank + "-" + hex(NFC sentence hashCode)`, 추가 질문은 `"x" + (i+1) + "-" + hex(...)`, 새 확정 항목은 불투명한 `"c-" + UUID`, 기존 확정 항목은 편집·순서 변경 뒤에도 id를 유지한다
  - `boolean suggestionAvailable(CaseEntity, Optional<QuestionCache>)`, `static String generationStatus(Optional<QuestionCache>)`
  - `void save(AuthContext, List<SaveQuestionsRequest.Item>)`, `void regenerate(AuthContext)`, `void replaceCaregiver(CaseEntity, List<String>)`
  - `PrepCardDto` 끝에 `List<Item> items, String generationStatus, boolean edited, boolean suggestionAvailable`
  - `record PrepCardDto.Item(String id, String sentence, String origin, boolean edited, ItemBasis basis)`, `ItemBasis(Evidence evidence, List<NoteBasis> notes)`, `NoteBasis(int week, String timeTagLabel, String itemLabel, String text)`
  - `PUT /me/prep-card/questions` 본문 `{"items":[{"id":"…"|null,"sentence":"…"}]}` → `PrepCardDto`
  - `POST /me/prep-card/regenerate` → `PrepCardDto`

항목 id에 문장 해시를 넣는 이유: 보호자가 편집하는 사이 정리안이 완성돼 캐시가 바뀌면, 옛 id가 새 질문의 근거를 잘못 이어받는다. 문장이 달라지면 id도 달라지므로 옛 id는 맞지 않고 "직접 적은 질문"으로 저장된다(근거를 지어내지 않는다).

확정 항목 id는 위치 번호가 아니라 근거 연결 키다. 최초 확정 때만 `c-UUID`를 발급하고, 이미 확정된 항목은 삭제·순서 변경·문장 편집 뒤에도 같은 id를 유지한다. 지워진 id는 다시 쓰지 않으며, 옛 추가 질문 API가 교체하는 보호자 항목도 새 id를 받는다.

- [x] **Step 1: 실패하는 테스트 작성** (`PrepCardControllerTest`에 추가)

주간 저장 본문은 기존 스냅샷 테스트의 도우미를 쓴다(`grep -rn "noChange" backend/api/src/test | head`). `seeded()`는 LLM이 꺼진 test 프로필이라 캐시는 `READY`다.

```java
    private List<Map<String, Object>> saveBody(Object... idSentencePairs) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < idSentencePairs.length; i += 2) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", idSentencePairs[i]);
            item.put("sentence", idSentencePairs[i + 1]);
            items.add(item);
        }
        return items;
    }

    @Test
    void cardExposesItemsWithBasisAndFlags() throws Exception {
        Onboarded o = seeded();
        JsonNode card = json(mapper, mvc.perform(getMe(o.token(), "/me/prep-card")).andExpect(status().isOk()).andReturn());

        assertEquals("TEMPLATE_ONLY", card.get("generationStatus").asText());
        assertFalse(card.get("edited").asBoolean());
        assertFalse(card.get("suggestionAvailable").asBoolean());
        JsonNode items = card.get("items");
        assertEquals(3, items.size());
        assertEquals("TEMPLATE", items.get(0).get("origin").asText());
        assertTrue(items.get(0).get("id").asText().startsWith("q1-"));
        assertEquals(card.get("questions").get(0).get("sentence").asText(), items.get(0).get("sentence").asText());
        assertTrue(items.get(0).get("basis").get("evidence").get("items").size() > 0);
        assertEquals(0, items.get(0).get("basis").get("notes").size());
        // 기존 필드 호환
        assertTrue(card.get("questions").get(0).has("evidence"));
        assertTrue(card.has("extraQuestions"));
    }

    @Test
    void legacyExtraQuestionsAppearAsCaregiverItems() throws Exception {
        Onboarded o = seeded();
        mvc.perform(putJson(o.token(), "/me/prep-card/extra", mapper, Map.of("questions", List.of("밤에 자주 깨시는데 괜찮을까요?"))))
            .andExpect(status().isOk());
        JsonNode items = json(mapper, mvc.perform(getMe(o.token(), "/me/prep-card")).andReturn()).get("items");

        assertEquals(4, items.size());
        assertEquals("CAREGIVER", items.get(3).get("origin").asText());
        assertEquals("밤에 자주 깨시는데 괜찮을까요?", items.get(3).get("sentence").asText());
        assertTrue(items.get(3).get("id").asText().startsWith("x1-"));
    }

    @Test
    void savingKeepsServerBasisForKnownIdsAndMarksEdits() throws Exception {
        Onboarded o = seeded();
        JsonNode before = json(mapper, mvc.perform(getMe(o.token(), "/me/prep-card")).andReturn()).get("items");
        String firstId = before.get(0).get("id").asText();

        JsonNode card = json(mapper, mvc.perform(putJson(o.token(), "/me/prep-card/questions", mapper,
            Map.of("items", saveBody(firstId, "고친 첫 질문인데 괜찮을까요?", null, "직접 적은 질문인데 괜찮을까요?",
                "q9-unknown", "모르는 id 질문인데 괜찮을까요?"))))).andExpect(status().isOk()).andReturn());

        assertTrue(card.get("edited").asBoolean());
        JsonNode items = card.get("items");
        assertEquals(3, items.size());
        assertEquals("TEMPLATE", items.get(0).get("origin").asText());
        assertTrue(items.get(0).get("edited").asBoolean());
        assertEquals(before.get(0).get("basis").get("evidence"), items.get(0).get("basis").get("evidence"));
        assertEquals("CAREGIVER", items.get(1).get("origin").asText());
        assertEquals("CAREGIVER", items.get(2).get("origin").asText());
        assertEquals(0, items.get(2).get("basis").get("evidence").get("items").size());
        assertEquals(0, card.get("extraQuestions").size());
    }

    @Test
    void savingValidatesCountBlankAndLength() throws Exception {
        Onboarded o = seeded();
        List<Object> nine = new ArrayList<>();
        for (int i = 0; i < 9; i++) { nine.add(null); nine.add("질문 " + i + " 괜찮을까요?"); }
        mvc.perform(putJson(o.token(), "/me/prep-card/questions", mapper, Map.of("items", saveBody(nine.toArray()))))
            .andExpect(status().isBadRequest());
        mvc.perform(putJson(o.token(), "/me/prep-card/questions", mapper, Map.of("items", saveBody(null, "   "))))
            .andExpect(status().isBadRequest());
        mvc.perform(putJson(o.token(), "/me/prep-card/questions", mapper, Map.of("items", saveBody(null, "가".repeat(201)))))
            .andExpect(status().isBadRequest());
    }

    @Test
    void confirmedListSurvivesANewWeekAndRegenerateRestoresTheSuggestion() throws Exception {
        Onboarded o = seeded();
        mvc.perform(putJson(o.token(), "/me/prep-card/questions", mapper,
            Map.of("items", saveBody(null, "직접 적은 질문인데 괜찮을까요?")))).andExpect(status().isOk());

        clock.advanceSeconds(60);
        // 기존 스냅샷 테스트 도우미로 현재 주(6주) 기록을 다시 저장한다
        mvc.perform(putJson(o.token(), "/me/weeks/6", mapper, noChangeWeek())).andExpect(status().isOk());

        JsonNode after = json(mapper, mvc.perform(getMe(o.token(), "/me/prep-card")).andReturn());
        assertEquals(1, after.get("items").size());
        assertEquals("직접 적은 질문인데 괜찮을까요?", after.get("items").get(0).get("sentence").asText());
        assertTrue(after.get("suggestionAvailable").asBoolean());

        JsonNode regenerated = json(mapper, mvc.perform(authed(post("/me/prep-card/regenerate"), o.token()))
            .andExpect(status().isOk()).andReturn());
        assertFalse(regenerated.get("edited").asBoolean());
        assertFalse(regenerated.get("suggestionAvailable").asBoolean());
        JsonNode items = regenerated.get("items");
        assertEquals("TEMPLATE", items.get(0).get("origin").asText());
        assertEquals("직접 적은 질문인데 괜찮을까요?", items.get(items.size() - 1).get("sentence").asText());
        assertEquals("CAREGIVER", items.get(items.size() - 1).get("origin").asText());
    }

    @Test
    void extraEndpointReplacesCaregiverItemsWhenAListIsConfirmed() throws Exception {
        Onboarded o = seeded();
        JsonNode before = json(mapper, mvc.perform(getMe(o.token(), "/me/prep-card")).andReturn()).get("items");
        mvc.perform(putJson(o.token(), "/me/prep-card/questions", mapper, Map.of("items",
            saveBody(before.get(0).get("id").asText(), before.get(0).get("sentence").asText(), null, "옛 질문인데 괜찮을까요?"))));

        mvc.perform(putJson(o.token(), "/me/prep-card/extra", mapper, Map.of("questions", List.of("새 질문인데 괜찮을까요?"))))
            .andExpect(status().isOk());

        JsonNode items = json(mapper, mvc.perform(getMe(o.token(), "/me/prep-card")).andReturn()).get("items");
        assertEquals(2, items.size());
        assertEquals("TEMPLATE", items.get(0).get("origin").asText());
        assertEquals("새 질문인데 괜찮을까요?", items.get(1).get("sentence").asText());
    }
```

`clock.advanceSeconds`가 없으면 `MutableClock`에 추가한다(`advanceDays`와 같은 방식). `noChangeWeek()`는 기존 도우미 이름에 맞춘다.

- [x] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew :api:test --tests "nextvisit.api.questions.PrepCardControllerTest"`
Expected: FAIL — `items` 필드와 새 엔드포인트가 없다.

- [x] **Step 3: 구현**

마이그레이션 (PostgreSQL):

```sql
-- 2026-09-17 정리 설계 4.1: 보호자가 확정한 진료 질문 목록
ALTER TABLE cases ADD COLUMN confirmed_questions jsonb;
ALTER TABLE cases ADD COLUMN confirmed_at timestamp with time zone;
```

H2는 `jsonb` 대신 `json`을 쓴다(V1과 같은 규칙). 시각 타입은 `h2/V1__init.sql`의 `created_at` 타입을 그대로 따른다.

`CaseEntity`:

```java
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "confirmed_questions")
    private String confirmedQuestions;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    public String getConfirmedQuestions() { return confirmedQuestions; }
    public Instant getConfirmedAt() { return confirmedAt; }

    public void confirmQuestions(String json, Instant at) {
        this.confirmedQuestions = json;
        this.confirmedAt = at;
    }

    public void clearConfirmedQuestions() {
        this.confirmedQuestions = null;
        this.confirmedAt = null;
    }
```

`ConfirmedItem.java`:

```java
package nextvisit.api.questions;

import java.util.List;

/** 보호자에게 보이는 질문 한 줄. 확정 목록은 이 레코드의 JSON 배열로 cases에 저장된다. */
public record ConfirmedItem(String id, String sentence, String origin, boolean edited,
                            String type, List<String> items, QuestionCacheBody.SignalRef signal,
                            QuestionCacheBody.Basis basis) {

    public static ConfirmedItem caregiver(String id, String sentence) {
        return new ConfirmedItem(id, sentence, QuestionCacheBody.ORIGIN_CAREGIVER, false,
            null, List.of(), null, QuestionCacheBody.Basis.EMPTY);
    }

    public boolean isCaregiver() {
        return QuestionCacheBody.ORIGIN_CAREGIVER.equals(origin);
    }
}
```

`SaveQuestionsRequest.java`:

```java
package nextvisit.api.questions;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record SaveQuestionsRequest(@NotNull List<Item> items) {
    public record Item(String id, String sentence) {}
}
```

`QuestionListService.java`:

```java
package nextvisit.api.questions;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import nextvisit.api.auth.AuthContext;
import nextvisit.api.cases.CaseEntity;
import nextvisit.api.cases.CaseRepository;
import nextvisit.api.common.Json;
import nextvisit.api.common.ValidationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 2026-09-17 정리 설계 4.1~4.3: 보이는 목록, 보호자 확정, 다시 정리하기. */
@Service
@Transactional
public class QuestionListService {

    public static final int MAX_ITEMS = 8;
    public static final int MAX_LENGTH = 200;

    private final CaseRepository cases;
    private final QuestionCacheRepository caches;
    private final QuestionService questions;
    private final Json json;
    private final Clock clock;

    public QuestionListService(CaseRepository cases, QuestionCacheRepository caches, QuestionService questions,
                               Json json, Clock clock) {
        this.cases = cases;
        this.caches = caches;
        this.questions = questions;
        this.json = json;
        this.clock = clock;
    }

    public List<ConfirmedItem> visible(CaseEntity kase, QuestionCacheBody cache) {
        if (kase.getConfirmedQuestions() != null) {
            return List.of(json.fromJson(kase.getConfirmedQuestions(), ConfirmedItem[].class));
        }
        List<ConfirmedItem> out = new ArrayList<>();
        for (QuestionCacheBody.Q q : cache.questions()) {
            out.add(new ConfirmedItem(stableId("q" + q.rank(), q.sentence()), q.sentence(), q.originOrDefault(),
                false, q.type(), q.items(), q.signal(), q.basisOrEmpty()));
        }
        String[] extras = json.fromJson(kase.getExtraQuestions(), String[].class);
        for (int i = 0; i < extras.length; i++) {
            out.add(ConfirmedItem.caregiver(stableId("x" + (i + 1), extras[i]), extras[i]));
        }
        return out;
    }

    public boolean suggestionAvailable(CaseEntity kase, Optional<QuestionCache> cache) {
        return kase.getConfirmedAt() != null
            && cache.isPresent()
            && cache.get().getGeneratedAt().isAfter(kase.getConfirmedAt())
            && cache.get().getStatus() != QuestionCacheStatus.LLM_PENDING;
    }

    public static String generationStatus(Optional<QuestionCache> cache) {
        if (cache.isEmpty()) {
            return "TEMPLATE_ONLY";
        }
        return switch (cache.get().getStatus()) {
            case LLM_PENDING -> "PENDING";
            case LLM_DONE -> "DONE";
            case LLM_FAILED -> "FAILED";
            case READY -> "TEMPLATE_ONLY";
        };
    }

    public void save(AuthContext ctx, List<SaveQuestionsRequest.Item> given) {
        validate(given);
        CaseEntity kase = cases.findById(ctx.kase().getId()).orElseThrow();
        QuestionCacheBody cache = questions.current(kase.getId()).orElseGet(() -> questions.refresh(kase.getId()));
        Map<String, ConfirmedItem> current = new HashMap<>();
        for (ConfirmedItem item : visible(kase, cache)) {
            current.put(item.id(), item);
        }
        boolean wasConfirmed = kase.getConfirmedQuestions() != null;
        List<ConfirmedItem> saved = new ArrayList<>();
        for (SaveQuestionsRequest.Item item : given) {
            String sentence = item.sentence().strip();
            ConfirmedItem base = item.id() == null ? null : current.get(item.id());
            if (base == null) {
                saved.add(ConfirmedItem.caregiver(newConfirmedId(), sentence));
            } else if (!wasConfirmed && base.isCaregiver()) {
                saved.add(ConfirmedItem.caregiver(newConfirmedId(), sentence));
            } else if (!wasConfirmed) {
                saved.add(new ConfirmedItem(newConfirmedId(), sentence, base.origin(),
                    !sentence.equals(base.sentence()), base.type(), base.items(), base.signal(), base.basis()));
            } else if (base.isCaregiver()) {
                saved.add(ConfirmedItem.caregiver(base.id(), sentence));
            } else {
                saved.add(new ConfirmedItem(base.id(), sentence, base.origin(),
                    base.edited() || !sentence.equals(base.sentence()),
                    base.type(), base.items(), base.signal(), base.basis()));
            }
        }
        kase.confirmQuestions(json.toJson(saved), Instant.now(clock));
        kase.setExtraQuestions("[]");
        cases.save(kase);
    }

    public void regenerate(AuthContext ctx) {
        CaseEntity kase = cases.findById(ctx.kase().getId()).orElseThrow();
        if (kase.getConfirmedQuestions() == null) {
            questions.refresh(kase.getId());
            return;
        }
        Optional<QuestionCache> cache = caches.findByCaseId(kase.getId());
        boolean needRefresh = cache.isEmpty() || !cache.get().getGeneratedAt().isAfter(kase.getConfirmedAt());
        List<String> caregiver = confirmed(kase).stream().filter(ConfirmedItem::isCaregiver)
            .map(ConfirmedItem::sentence).toList();
        kase.clearConfirmedQuestions();
        kase.setExtraQuestions(json.toJson(caregiver));
        cases.save(kase);
        if (needRefresh) {
            questions.refresh(kase.getId());
        }
    }

    /** 옛 추가 질문 API 호환: 확정 목록이 있으면 그 안의 보호자 작성 질문만 바꾼다. */
    public void replaceCaregiver(CaseEntity kase, List<String> sentences) {
        List<ConfirmedItem> kept = new ArrayList<>(confirmed(kase).stream().filter(i -> !i.isCaregiver()).toList());
        for (String sentence : sentences) {
            kept.add(ConfirmedItem.caregiver(newConfirmedId(), sentence));
        }
        kase.confirmQuestions(json.toJson(kept), kase.getConfirmedAt());
    }

    private List<ConfirmedItem> confirmed(CaseEntity kase) {
        return List.of(json.fromJson(kase.getConfirmedQuestions(), ConfirmedItem[].class));
    }

    private static void validate(List<SaveQuestionsRequest.Item> given) {
        if (given.size() > MAX_ITEMS) {
            throw new ValidationException("질문은 " + MAX_ITEMS + "개까지입니다");
        }
        for (SaveQuestionsRequest.Item item : given) {
            if (item == null || item.sentence() == null || item.sentence().isBlank()) {
                throw new ValidationException("빈 질문은 넣을 수 없습니다");
            }
            if (item.sentence().strip().length() > MAX_LENGTH) {
                throw new ValidationException("질문은 " + MAX_LENGTH + "자까지입니다");
            }
        }
    }

    static String stableId(String prefix, String sentence) {
        String normalized = Normalizer.normalize(sentence == null ? "" : sentence, Normalizer.Form.NFC);
        return prefix + "-" + Integer.toHexString(normalized.hashCode());
    }
}
```

`ValidationException`의 실제 패키지는 `PrepCardService`의 import를 따른다.

`PrepCardDto.java`:

```java
public record PrepCardDto(int week, LocalDate nextVisitDate, List<Question> questions, List<String> extraQuestions,
                          String emptyMessage, List<String> therapistGlance,
                          List<Item> items, String generationStatus, boolean edited, boolean suggestionAvailable) {
    public record Question(int rank, String type, String sentence, String source, Evidence evidence) {}
    public record Evidence(List<EvidenceItem> items, SignalEvidence signal) {}
    public record EvidenceItem(String code, String label, String axis, String axisLabel, List<TrajectoryDto.Point> values) {}
    public record SignalEvidence(String action, String actionLabel, String kind, String kindLabel, List<Integer> weeks, int window) {}
    public record Item(String id, String sentence, String origin, boolean edited, ItemBasis basis) {}
    public record ItemBasis(Evidence evidence, List<NoteBasis> notes) {}
    public record NoteBasis(int week, String timeTagLabel, String itemLabel, String text) {}
}
```

`PrepCardService` 변경:
1. 생성자에 `QuestionListService list`, `QuestionCacheRepository caches`를 받는다.
2. `card`의 첫 줄을 `CaseEntity kase = cases.findById(ctx.kase().getId()).orElseThrow();`로 바꾼다. 같은 요청에서 저장한 확정 목록을 읽어야 하기 때문이다(`ctx.kase()`는 요청 시작 때 읽은 값이다).
3. 기존 질문별 근거 계산을 `private PrepCardDto.Evidence evidence(String type, List<String> items, QuestionCacheBody.SignalRef signal, List<Snapshot> snaps)`로 뽑아내고, 기존 `questions` 조립이 이 함수를 쓰게 한다. `type`이 `null`이면 축 목록을 빈 목록으로 본다.
4. 반환 직전:

```java
        Optional<QuestionCache> row = caches.findByCaseId(kase.getId());
        List<PrepCardDto.Item> items = list.visible(kase, cache).stream()
            .map(v -> new PrepCardDto.Item(v.id(), v.sentence(), v.origin(), v.edited(), basisOf(v, snaps)))
            .toList();
        return new PrepCardDto(weeks.currentWeek(kase.getStartDate()), kase.getNextVisitDate(), qs, extra,
            qs.isEmpty() ? EMPTY_MESSAGE : null, glance(r, preferredCodes),
            items, QuestionListService.generationStatus(row), kase.getConfirmedQuestions() != null,
            list.suggestionAvailable(kase, row));
```

```java
    private PrepCardDto.ItemBasis basisOf(ConfirmedItem item, List<Snapshot> snaps) {
        PrepCardDto.Evidence evidence = evidence(item.type(), item.items(), item.signal(), snaps);
        Set<Integer> weeks = new HashSet<>(item.basis().noteWeeks());
        List<PrepCardDto.NoteBasis> notes = new ArrayList<>();
        for (Snapshot s : snaps) {
            if (weeks.contains(s.getWeek())) {
                for (SynthesisInput.NoteLine line : SynthesisInputAssembler.noteLines(s.getWeek(),
                        json.fromJson(s.getBody(), SnapshotBody.class))) {
                    notes.add(new PrepCardDto.NoteBasis(line.week(), line.timeTagLabel(), line.itemLabel(), line.text()));
                }
            }
        }
        return new PrepCardDto.ItemBasis(evidence, notes);
    }
```

5. `saveExtra`: 저장 직전에 확정 목록이 있으면 `list.replaceCaregiver(kase, cleaned)`를 부르고 `extra_questions`는 `[]`로 둔다. 없으면 기존대로 저장한다.

`PrepCardController`에 추가:

```java
    @PutMapping("/me/prep-card/questions")
    public PrepCardDto saveQuestions(@CurrentGuardian AuthContext ctx, @RequestBody @Valid SaveQuestionsRequest req) {
        list.save(ctx, req.items());
        return service.card(ctx);
    }

    @PostMapping("/me/prep-card/regenerate")
    public PrepCardDto regenerate(@CurrentGuardian AuthContext ctx) {
        list.regenerate(ctx);
        return service.card(ctx);
    }
```

(생성자에 `QuestionListService list` 추가, `PostMapping` import.)

- [x] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew clean test`
Expected: 전체 PASS.

- [x] **Step 5: 인수인계 칸 갱신 후 커밋**

```bash
git add -A backend docs/superpowers/plans/2026-09-18-caregiver-question-synthesis.md
git commit -m "feat: let caregivers confirm, edit and regenerate their visit question list"
```

---

### Task 7: 치료사 요약의 질문 근거

**Files:**
- Modify: `backend/api/src/main/java/nextvisit/api/therapist/TherapistSummaryDto.java`
- Modify: `backend/api/src/main/java/nextvisit/api/therapist/TherapistSummaryService.java`
- Test: 치료사 요약 컨트롤러 테스트 (`grep -rln "/t/" backend/api/src/test/java/nextvisit/api/therapist`)

**Interfaces:**
- Consumes: `QuestionListService.visible(...)`, `ConfirmedItem.isCaregiver()` (Task 6)
- Produces: `TherapistSummaryDto`에 `List<QuestionDetail> questionDetails`(`disclaimer` 앞) + `record QuestionDetail(String sentence, String origin, List<Integer> noteWeeks)`

- [x] **Step 1: 실패하는 테스트 작성**

기존 치료사 요약 테스트 파일에 추가한다. 케이스 준비와 링크 발급은 그 파일의 기존 도우미를 쓴다.

```java
    @Test
    void summarySplitsCaregiverQuestionsAndCarriesQuestionDetails() throws Exception {
        // 준비: seeded 케이스 → GET /me/prep-card 로 첫 항목 id를 얻는다
        // PUT /me/prep-card/questions {items:[{id:첫 id, sentence:첫 문장}, {id:null, sentence:"직접 적은 질문인데 괜찮을까요?"}]}
        // 치료사 링크 발급 → GET /t/{token}
        // 검증:
        //   questions == [첫 문장]
        //   extraQuestions == ["직접 적은 질문인데 괜찮을까요?"]
        //   questionDetails.size() == 2
        //   questionDetails[0].origin == "TEMPLATE", questionDetails[0].noteWeeks == []
        //   questionDetails[1].origin == "CAREGIVER"
    }
```

위 주석의 각 줄을 실제 MockMvc 호출과 `assertEquals`로 옮긴다. 쓰는 도우미·경로는 같은 파일에 이미 있다.

- [x] **Step 2: 실패 확인**

Run: `cd backend && ./gradlew :api:test --tests "nextvisit.api.therapist.*"`
Expected: FAIL — `questionDetails`가 없다.

- [x] **Step 3: 구현**

`TherapistSummaryDto`:

```java
    List<AuthorChange> authorChanges,
    List<QuestionDetail> questionDetails,
    String disclaimer
) {
    ...
    public record QuestionDetail(String sentence, String origin, List<Integer> noteWeeks) {}
```

`TherapistSummaryService`: 생성자에 `QuestionListService list`를 받고, 기존 `qs`와 추가 질문 계산을 바꾼다.

```java
        QuestionCacheBody cache = questions.current(kase.getId()).orElse(new QuestionCacheBody(List.of(), 0));
        List<ConfirmedItem> visible = list.visible(kase, cache);
        List<String> qs = visible.stream().filter(i -> !i.isCaregiver()).map(ConfirmedItem::sentence).toList();
        List<String> extra = visible.stream().filter(ConfirmedItem::isCaregiver).map(ConfirmedItem::sentence).toList();
        List<TherapistSummaryDto.QuestionDetail> details = visible.stream()
            .map(i -> new TherapistSummaryDto.QuestionDetail(i.sentence(), i.origin(), i.basis().noteWeeks()))
            .toList();
```

`kase`가 요청 시작 때 읽은 값이면 `cases.findById(...)`로 다시 읽는다. 생성자 호출에 `details`를 `authorChanges` 다음에 넣는다.

- [x] **Step 4: 통과 확인**

Run: `cd backend && ./gradlew clean test`
Expected: 전체 PASS.

- [x] **Step 5: 인수인계 칸 갱신 후 커밋**

```bash
git add -A backend docs/superpowers/plans/2026-09-18-caregiver-question-synthesis.md
git commit -m "feat: show therapists which caregiver weeks each question came from"
```

---

### Task 8: 프런트 타입, 저장·다시 정리 요청, 정리 중 재조회

**Files:**
- Modify: `frontend/src/lib/types.ts`
- Modify: `frontend/src/lib/queries.ts`
- Create: `frontend/src/lib/prepPolling.ts`
- Test: `frontend/src/lib/prepPolling.test.ts`

**Interfaces:**
- Produces:
  - 타입 `QuestionOrigin = 'TEMPLATE' | 'LLM' | 'CAREGIVER'`, `GenerationStatus = 'PENDING' | 'DONE' | 'FAILED' | 'TEMPLATE_ONLY'`
  - `interface NoteBasis { week; timeTagLabel: string | null; itemLabel: string | null; text }`, `ItemBasis { evidence: Evidence; notes: NoteBasis[] }`, `PrepItem { id; sentence; origin: QuestionOrigin; edited: boolean; basis: ItemBasis }`
  - `PrepCard`에 선택 필드 `items?`, `generationStatus?`, `edited?`, `suggestionAvailable?` (옛 백엔드와 호환)
  - `TherapistQuestionDetail { sentence; origin: QuestionOrigin; noteWeeks: number[] }`, `TherapistSummary.questionDetails?`
  - `nextPrepPoll(status, pendingSince, now)` → `{ interval: number | false; pendingSince: number | null }`
  - `useSaveQuestions()` — `mutate(items: { id: string | null; sentence: string }[])`, 성공 시 응답 카드로 캐시 교체
  - `useRegenerateQuestions()` — `mutate()`, 성공 시 응답 카드로 캐시 교체

- [x] **Step 1: 실패하는 테스트 작성**

```ts
import { describe, expect, it } from 'vitest';
import { nextPrepPoll, PREP_POLL_LIMIT_MS, PREP_POLL_MS } from './prepPolling';

describe('nextPrepPoll', () => {
  it('정리 중이면 5초마다 다시 부르고 시작 시각을 기억한다', () => {
    expect(nextPrepPoll('PENDING', null, 1000)).toEqual({ interval: PREP_POLL_MS, pendingSince: 1000 });
    expect(nextPrepPoll('PENDING', 1000, 1000 + 60_000)).toEqual({ interval: PREP_POLL_MS, pendingSince: 1000 });
  });

  it('5분이 지나면 멈춘다', () => {
    expect(nextPrepPoll('PENDING', 0, PREP_POLL_LIMIT_MS)).toEqual({ interval: false, pendingSince: 0 });
  });

  it('정리 중이 아니면 멈추고 시작 시각을 지운다', () => {
    for (const status of ['DONE', 'FAILED', 'TEMPLATE_ONLY', undefined] as const) {
      expect(nextPrepPoll(status, 1000, 2000)).toEqual({ interval: false, pendingSince: null });
    }
  });
});
```

- [x] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- src/lib/prepPolling.test.ts`
Expected: FAIL — 모듈이 없다.

- [x] **Step 3: 구현**

`prepPolling.ts`:

```ts
import type { GenerationStatus } from './types';

export const PREP_POLL_MS = 5_000;
export const PREP_POLL_LIMIT_MS = 5 * 60_000;

/** 정리 중일 때만 짧게 다시 부르고, 5분이 지나면 멈춘다(설계 5.1). */
export function nextPrepPoll(
  status: GenerationStatus | undefined,
  pendingSince: number | null,
  now: number,
): { interval: number | false; pendingSince: number | null } {
  if (status !== 'PENDING') return { interval: false, pendingSince: null };
  const since = pendingSince ?? now;
  return { interval: now - since < PREP_POLL_LIMIT_MS ? PREP_POLL_MS : false, pendingSince: since };
}
```

`types.ts`의 `PrepCard` 앞뒤:

```ts
export type QuestionOrigin = 'TEMPLATE' | 'LLM' | 'CAREGIVER';
export type GenerationStatus = 'PENDING' | 'DONE' | 'FAILED' | 'TEMPLATE_ONLY';
export interface NoteBasis { week: number; timeTagLabel: string | null; itemLabel: string | null; text: string }
export interface ItemBasis { evidence: Evidence; notes: NoteBasis[] }
export interface PrepItem { id: string; sentence: string; origin: QuestionOrigin; edited: boolean; basis: ItemBasis }
export interface PrepCard {
  week: number;
  nextVisitDate: string | null;
  questions: PrepQuestion[];
  extraQuestions: string[];
  emptyMessage: string | null;
  therapistGlance: string[];
  /** 2026-09-17 이후 백엔드만 보낸다. 없으면 옛 필드로 읽기 전용 화면을 그린다. */
  items?: PrepItem[];
  generationStatus?: GenerationStatus;
  edited?: boolean;
  suggestionAvailable?: boolean;
}
```

`TherapistSummary`에 `questionDetails?: TherapistQuestionDetail[];`를 추가하고 `export interface TherapistQuestionDetail { sentence: string; origin: QuestionOrigin; noteWeeks: number[] }`를 선언한다.

`queries.ts`: `usePrepCard`를 함수로 바꾸고(다른 호출부의 인자 형태는 그대로), 두 뮤테이션을 추가한다.

```ts
export function usePrepCard(enabled = true) {
  const pendingSince = useRef<number | null>(null);
  return useQuery({
    queryKey: QK.prepCard,
    queryFn: () => api.get<PrepCard>('/me/prep-card'),
    enabled,
    refetchInterval: (query) => {
      const next = nextPrepPoll(query.state.data?.generationStatus, pendingSince.current, Date.now());
      pendingSince.current = next.pendingSince;
      return next.interval;
    },
  });
}

export interface SaveQuestionItem { id: string | null; sentence: string }

export function useSaveQuestions() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (items: SaveQuestionItem[]) => api.put<PrepCard>('/me/prep-card/questions', { items }),
    onSuccess: (card) => { qc.setQueryData(QK.prepCard, card); },
  });
}

export function useRegenerateQuestions() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<PrepCard>('/me/prep-card/regenerate'),
    onSuccess: (card) => { qc.setQueryData(QK.prepCard, card); },
  });
}
```

(`import { useRef } from 'react';`, `import { nextPrepPoll } from './prepPolling';`)

- [x] **Step 4: 통과 확인**

Run: `npm --prefix frontend test && npm --prefix frontend run typecheck`
Expected: PASS.

- [x] **Step 5: 인수인계 칸 갱신 후 커밋**

```bash
git add frontend docs/superpowers/plans/2026-09-18-caregiver-question-synthesis.md
git commit -m "feat: add question list types, save and regenerate requests, and pending polling"
```

---

### Task 9: 진료 준비 화면 — 하나의 목록, 근거, 편집, 상태 안내

**Files:**
- Create: `frontend/src/screens/prep/EvidenceView.tsx` (현재 `PrepCard.tsx`의 근거 표 JSX를 옮김)
- Create: `frontend/src/screens/prep/QuestionItemView.tsx`
- Create: `frontend/src/screens/prep/QuestionEditor.tsx`
- Create: `frontend/src/screens/prep/QuestionStatus.tsx`
- Create: `frontend/src/screens/prep/LegacyQuestions.tsx` (옛 백엔드용 읽기 전용)
- Modify: `frontend/src/screens/PrepCard.tsx`
- Test: `frontend/src/screens/PrepCard.test.tsx` (다시 작성)

**Interfaces:**
- Consumes: Task 8의 타입과 훅
- Produces: 화면 문구(아래 표). 문구 화이트리스트 테스트가 이 문구를 고정한다.

| 자리 | 문구 |
| --- | --- |
| 목록 제목 (h2) | 진료실에서 여쭤볼 것 |
| 출처 (글자) | 기록에서 정리 / 관찰에서 나온 질문 / 직접 적은 질문, 고친 항목은 뒤에 ` · 고침` |
| 항목 머리 | `질문 {n} · {출처}` |
| 근거 펼치기 | 이 질문의 근거 |
| 근거 소제목 | 보호자 기록 / 관찰 기록 |
| 원문 머리 | `{주}주 · {시간대} · {항목} 메모` (없는 조각은 생략) |
| 편집 시작 | 질문 고치기 (목록이 비었으면 `+ 질문 적기`) |
| 편집 제목 (h2) | 질문 고치기 |
| 편집 칸 라벨 | `질문 {n}` |
| 편집 버튼 | 지우기(`aria-label="질문 {n} 지우기"`), + 질문 추가, 저장, 취소 |
| 개수 한도 | 여덟 개까지 넣으실 수 있습니다. |
| 정리 중 | 기록을 바탕으로 질문을 정리하고 있어요. |
| 편집 중 정리 완료 | 정리안이 준비됐어요. 취소하시면 정리안을 보여드려요. |
| 새 정리안 | 새 기록이 반영된 정리안이 있어요. / 버튼 다시 정리하기 / 직접 적으신 질문은 그대로 남아요. |
| 템플릿만 | 관찰 기록에서 나온 질문을 보여드려요. (확정 목록이 없을 때만) |
| 저장 성공 | 질문을 저장했습니다. |
| 저장 실패 | 질문을 저장하지 못했습니다. 입력한 내용은 그대로 있습니다. 다시 저장해 주세요. |
| 다시 정리 실패 | 다시 정리하지 못했습니다. 잠시 뒤 다시 눌러 주세요. |

- [x] **Step 1: 실패하는 테스트 작성**

`PrepCard.test.tsx`를 새 필드 기준으로 다시 쓴다. 파일 머리(MSW 서버, `renderIt`, `BASE`)는 그대로 둔다. 표본:

```ts
const evidence = {
  items: [{
    code: 'ambulation', label: '집 안에서 걷기', axis: 'LEVEL', axisLabel: '도움 수준',
    values: [{ week: 5, value: 1, label: '손 잡아드림', source: 'CONFIRMED' as const }],
  }],
  signal: null,
};
const base: Card = {
  week: 6, nextVisitDate: '2026-09-08',
  questions: [{ rank: 1, type: 'SYNTHESIS', source: 'LLM', sentence: '합성 정리 질문인데 괜찮을까요?', evidence }],
  extraQuestions: [], emptyMessage: null, therapistGlance: ['화장실 이용 · 도움 수준 4주째 유지'],
  generationStatus: 'DONE', edited: false, suggestionAvailable: false,
  items: [
    { id: 'q1-a', sentence: '합성 정리 질문인데 괜찮을까요?', origin: 'LLM', edited: false,
      basis: { evidence, notes: [{ week: 3, timeTagLabel: '오후', itemLabel: null, text: '합성 원문 메모' }] } },
    { id: 'x1-b', sentence: '직접 적은 합성 질문인데 괜찮을까요?', origin: 'CAREGIVER', edited: false,
      basis: { evidence: { items: [], signal: null }, notes: [] } },
  ],
};
```

`source`의 실제 타입이 문자열 리터럴 유니온이면 그에 맞춘다(`ObservationValue`의 `Point` 타입 확인).

작성할 테스트(각각 실제 `it` 블록으로):

1. **목록과 출처**: `진료실에서 여쭤볼 것` 제목, `질문 1 · 기록에서 정리`, `질문 2 · 직접 적은 질문`, 두 문장이 보인다.
2. **근거 펼치기**: 첫 항목의 `이 질문의 근거`를 누르면 `3주 · 오후`, `합성 원문 메모`, `보호자 기록`, `관찰 기록`, `집 안에서 걷기 · 도움 수준`이 보인다. 두 번째 항목에는 근거 버튼이 없다.
3. **편집 저장**: `질문 고치기` → `질문 1` 칸을 `고친 질문인데 괜찮을까요?`로 바꾸고 `+ 질문 추가` → `질문 3` 칸에 `새 질문인데 괜찮을까요?` → `질문 2 지우기` → `저장`. MSW `PUT /me/prep-card/questions`가 받은 본문이 정확히
   `{"items":[{"id":"q1-a","sentence":"고친 질문인데 괜찮을까요?"},{"id":null,"sentence":"새 질문인데 괜찮을까요?"}]}`이고, 응답 카드(`edited: true`, 해당 두 항목)가 그려지며 `질문을 저장했습니다.`가 보인다.
4. **저장 실패 시 입력 보존**: PUT이 500이면 `질문을 저장하지 못했습니다…`가 `role=alert`로 보이고 편집 칸의 값이 그대로다.
5. **취소**: 편집 후 `취소`하면 원래 목록으로 돌아가고 PUT이 호출되지 않는다.
6. **정리 중 안내**: `generationStatus: 'PENDING'`이면 `기록을 바탕으로 질문을 정리하고 있어요.`가 `role=status`로 보이고 목록도 그대로 보인다.
7. **편집 중 정리 완료**: PENDING 상태에서 편집을 시작한 뒤 쿼리 캐시를 `DONE`으로 바꾸면(`queryClient.setQueryData`) `정리안이 준비됐어요. 취소하시면 정리안을 보여드려요.`가 보이고 편집 칸 값은 그대로다.
8. **새 정리안**: `edited: true, suggestionAvailable: true`이면 `새 기록이 반영된 정리안이 있어요.`와 `다시 정리하기`가 보이고, 누르면 `POST /me/prep-card/regenerate`가 한 번 호출되며 응답 카드가 그려진다. 실패하면 `다시 정리하지 못했습니다…`.
9. **템플릿만**: `generationStatus: 'TEMPLATE_ONLY', edited: false`이면 `관찰 기록에서 나온 질문을 보여드려요.`. `edited: true`면 이 문구가 없다.
10. **8개 한도**: 항목 8개인 카드에서 편집을 열면 `+ 질문 추가` 대신 `여덟 개까지 넣으실 수 있습니다.`.
11. **옛 백엔드**: `items`가 없는 카드(`base`에서 새 필드 4개를 지운 것)면 옛 질문과 추가 질문이 읽기 전용으로 보이고 `질문 고치기` 버튼이 없다.
12. **문구 화이트리스트 — 빈 상태**: `items: []`, `emptyMessage: null`, 요약 없음이면 `main.textContent`가 정확히
    `'← 뒤로진료 준비진료실에서 여쭤볼 것+ 질문 적기치료사에게 보여드리기'` 이다. (`TherapistLinkPanel`의 기존 문구가 이와 다르면 기존 화이트리스트 테스트의 꼬리 부분을 그대로 옮겨 붙인다.)
13. **문구 화이트리스트 — 질문이 있는 상태**: 기존 테스트처럼 표본 값에서 기대 문자열을 조립해 접힌 상태 전체 텍스트를 고정한다.

- [x] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- src/screens/PrepCard.test.tsx`
Expected: FAIL — 새 문구와 편집 화면이 없다.

- [x] **Step 3: 구현**

`EvidenceView.tsx` — 현재 `PrepCard.tsx`의 `<Collapse label="이 질문의 관찰 근거">` 안 `<div className="flex flex-col gap-4">…</div>` 내용을 그대로 옮긴 컴포넌트:

```tsx
import type { Evidence } from '../../lib/types';
import { ObservationValue } from '../../ui/ObservationValue';
import { ScrollRegion } from '../../ui/ScrollRegion';

export function EvidenceView({ evidence }: { evidence: Evidence }) {
  return (
    <div className="flex flex-col gap-4">
      {evidence.items.map((it) => (
        <div key={`${it.code}-${it.axis}`}>
          <p className="text-small text-ink-soft">{it.label} · {it.axisLabel}</p>
          <ScrollRegion label={`${it.label} ${it.axisLabel} 주차별 기록`}>
            <div className="flex min-w-max gap-5 pt-1">
              {it.values.map((p) => (
                <div key={p.week} className="min-w-[92px]">
                  <p className="text-small text-ink-faint">{p.week}주</p>
                  <ObservationValue point={p} />
                </div>
              ))}
            </div>
          </ScrollRegion>
        </div>
      ))}
      {evidence.signal ? (
        <p className="text-small text-ink-soft">
          {evidence.signal.actionLabel} · {evidence.signal.kindLabel} — {evidence.signal.weeks.join('주, ')}주
        </p>
      ) : null}
    </div>
  );
}
```

`QuestionItemView.tsx`:

```tsx
import type { NoteBasis, PrepItem, QuestionOrigin } from '../../lib/types';
import { Collapse } from '../../ui/Collapse';
import { EvidenceView } from './EvidenceView';

export const ORIGIN_LABEL: Record<QuestionOrigin, string> = {
  LLM: '기록에서 정리',
  TEMPLATE: '관찰에서 나온 질문',
  CAREGIVER: '직접 적은 질문',
};

function noteHeading(n: NoteBasis): string {
  return [`${n.week}주`, n.timeTagLabel, n.itemLabel ? `${n.itemLabel} 메모` : null].filter(Boolean).join(' · ');
}

export function QuestionItemView({ item, index }: { item: PrepItem; index: number }) {
  const { evidence, notes } = item.basis;
  const hasEvidence = evidence.items.length > 0 || evidence.signal !== null;
  const hasNotes = notes.length > 0;
  return (
    <article className="note-surface" aria-labelledby={`question-${item.id}`}>
      <p className="mb-2 text-small text-ink-soft">
        질문 {index + 1} · {ORIGIN_LABEL[item.origin]}{item.edited ? ' · 고침' : ''}
      </p>
      <h3 id={`question-${item.id}`} className="text-[22px] font-semibold leading-snug">{item.sentence}</h3>
      {hasEvidence || hasNotes ? (
        <div className="pt-1">
          <Collapse label="이 질문의 근거">
            <div className="flex flex-col gap-5">
              {hasNotes ? (
                <div>
                  <p className="text-small text-ink-soft">보호자 기록</p>
                  <ul className="flex flex-col gap-2 pt-1">
                    {notes.map((n, i) => (
                      <li key={`${n.week}-${i}`}>
                        <p className="text-small text-ink-faint">{noteHeading(n)}</p>
                        {/* 원문 그대로. 자르지도 다듬지도 않는다. */}
                        <p className="whitespace-pre-wrap">{n.text}</p>
                      </li>
                    ))}
                  </ul>
                </div>
              ) : null}
              {hasEvidence ? (
                <div>
                  <p className="text-small text-ink-soft">관찰 기록</p>
                  <EvidenceView evidence={evidence} />
                </div>
              ) : null}
            </div>
          </Collapse>
        </div>
      ) : null}
    </article>
  );
}
```

`QuestionEditor.tsx`:

```tsx
import { useRef, useState } from 'react';
import type { PrepItem } from '../../lib/types';
import { useSaveQuestions } from '../../lib/queries';
import { Button } from '../../ui/Button';
import { Notice } from '../../ui/Notice';

const MAX_ITEMS = 8;
const MAX_LEN = 200;

interface Draft { key: number; id: string | null; sentence: string }

export function QuestionEditor(
  { items, onCancel, onSaved }: { items: PrepItem[]; onCancel: () => void; onSaved: () => void },
) {
  const save = useSaveQuestions();
  const nextKey = useRef(items.length);
  const version = useRef(0);
  const [drafts, setDrafts] = useState<Draft[]>(
    () => items.map((it, i) => ({ key: i, id: it.id, sentence: it.sentence })),
  );

  const touch = () => { version.current++; };
  const change = (key: number, value: string) => {
    touch();
    setDrafts((ds) => ds.map((d) => (d.key === key ? { ...d, sentence: value.slice(0, MAX_LEN) } : d)));
  };
  const remove = (key: number) => { touch(); setDrafts((ds) => ds.filter((d) => d.key !== key)); };
  const add = () => {
    touch();
    const key = nextKey.current++;
    setDrafts((ds) => [...ds, { key, id: null, sentence: '' }]);
  };
  const submit = () => {
    const submitted = version.current;
    save.mutate(
      drafts.map((d) => ({ id: d.id, sentence: d.sentence.trim() })).filter((d) => d.sentence.length > 0),
      { onSuccess: () => { if (submitted === version.current) onSaved(); } },
    );
  };

  return (
    <section className="note-surface" aria-labelledby="question-editor-title">
      <h2 id="question-editor-title" className="font-semibold">질문 고치기</h2>
      <ol className="flex flex-col gap-4 pt-4">
        {drafts.map((d, i) => (
          <li key={d.key} className="flex flex-col gap-2">
            <textarea
              rows={3}
              aria-label={`질문 ${i + 1}`}
              className="min-h-[96px] w-full resize-y rounded-xl border border-control bg-paper p-4 [field-sizing:content]"
              value={d.sentence}
              maxLength={MAX_LEN}
              onChange={(e) => change(d.key, e.target.value)}
            />
            <Button variant="plain" aria-label={`질문 ${i + 1} 지우기`} onClick={() => remove(d.key)}>지우기</Button>
          </li>
        ))}
      </ol>
      <div className="flex flex-col gap-3 pt-4">
        {drafts.length < MAX_ITEMS
          ? <Button variant="plain" onClick={add}>+ 질문 추가</Button>
          : <Notice>여덟 개까지 넣으실 수 있습니다.</Notice>}
        <Button disabled={save.isPending} onClick={submit}>{save.isPending ? '저장하는 중입니다…' : '저장'}</Button>
        <Button variant="plain" disabled={save.isPending} onClick={onCancel}>취소</Button>
      </div>
      {save.isError
        ? <Notice role="alert">질문을 저장하지 못했습니다. 입력한 내용은 그대로 있습니다. 다시 저장해 주세요.</Notice>
        : null}
    </section>
  );
}
```

`QuestionStatus.tsx`:

```tsx
import type { PrepCard } from '../../lib/types';
import { Button } from '../../ui/Button';
import { Notice } from '../../ui/Notice';

export function QuestionStatus({ card, editing, regenerating, regenerateFailed, onRegenerate }: {
  card: PrepCard; editing: boolean; regenerating: boolean; regenerateFailed: boolean; onRegenerate: () => void;
}) {
  const items = card.items ?? [];
  const pending = card.generationStatus === 'PENDING';
  const templateOnly = !card.edited && items.length > 0
    && (card.generationStatus === 'FAILED' || card.generationStatus === 'TEMPLATE_ONLY');
  return (
    <div aria-live="polite" className="flex flex-col gap-3">
      {pending && !editing ? <Notice role="status">기록을 바탕으로 질문을 정리하고 있어요.</Notice> : null}
      {templateOnly ? <Notice role="status">관찰 기록에서 나온 질문을 보여드려요.</Notice> : null}
      {card.suggestionAvailable && !editing ? (
        <div className="note-surface flex flex-col gap-3">
          <p>새 기록이 반영된 정리안이 있어요.</p>
          <Button disabled={regenerating} onClick={onRegenerate}>
            {regenerating ? '정리하는 중입니다…' : '다시 정리하기'}
          </Button>
          <p className="text-small text-ink-soft">직접 적으신 질문은 그대로 남아요.</p>
        </div>
      ) : null}
      {regenerateFailed ? <Notice role="alert">다시 정리하지 못했습니다. 잠시 뒤 다시 눌러 주세요.</Notice> : null}
    </div>
  );
}
```

`LegacyQuestions.tsx` — 옛 카드용 읽기 전용:

```tsx
import type { PrepCard } from '../../lib/types';
import { Collapse } from '../../ui/Collapse';
import { EvidenceView } from './EvidenceView';

export function LegacyQuestions({ card }: { card: PrepCard }) {
  return (
    <>
      {card.questions.length === 0 ? (
        card.emptyMessage ? <p className="pt-8">{card.emptyMessage}</p> : null
      ) : (
        <ol className="space-y-5">
          {card.questions.map((q) => (
            <li key={q.rank} className="min-w-0">
              <article className="note-surface" aria-labelledby={`question-${q.rank}`}>
                <p className="mb-2 text-small text-ink-soft">질문 {q.rank}</p>
                <h2 id={`question-${q.rank}`} className="text-[22px] font-semibold leading-snug">{q.sentence}</h2>
                <div className="pt-1">
                  <Collapse label="이 질문의 관찰 근거"><EvidenceView evidence={q.evidence} /></Collapse>
                </div>
              </article>
            </li>
          ))}
        </ol>
      )}
      {card.extraQuestions.length > 0 ? (
        <section className="note-surface">
          <h2 className="font-semibold">내가 더 여쭤보고 싶은 것</h2>
          <ul className="list-disc pt-3 pl-5">
            {card.extraQuestions.map((q) => <li key={q}>{q}</li>)}
          </ul>
        </section>
      ) : null}
    </>
  );
}
```

`PrepCard.tsx` 전체:

```tsx
import { useState } from 'react';
import { PageHeader } from '../ui/PageHeader';
import { AsyncState } from '../ui/AsyncState';
import { formatDate } from '../lib/format';
import { usePrepCard, useRegenerateQuestions } from '../lib/queries';
import { Button } from '../ui/Button';
import { Notice } from '../ui/Notice';
import { TherapistLinkPanel } from '../ui/TherapistLinkPanel';
import { LegacyQuestions } from './prep/LegacyQuestions';
import { QuestionEditor } from './prep/QuestionEditor';
import { QuestionItemView } from './prep/QuestionItemView';
import { QuestionStatus } from './prep/QuestionStatus';

export function PrepCard() {
  const query = usePrepCard();
  const { data } = query;
  const regenerate = useRegenerateQuestions();
  const [editing, setEditing] = useState(false);
  const [pendingAtEdit, setPendingAtEdit] = useState(false);
  const [saved, setSaved] = useState(false);

  if (!data) return <main className="app-page"><PageHeader title="진료 준비" backTo="/" />
    <AsyncState kind={query.isError ? 'error' : 'loading'} message={query.isError ? '진료 질문을 불러오지 못했습니다.' : '불러오는 중입니다…'}
      onRetry={query.isError ? () => { void query.refetch(); } : undefined} /></main>;

  const title = data.nextVisitDate ? `${formatDate(data.nextVisitDate)} 진료` : '진료 준비';
  const items = data.items;

  return (
    <main className="app-page space-y-8">
      <PageHeader backTo="/" title={title} focusKey="prep" />

      {items === undefined ? <LegacyQuestions card={data} /> : (
        <section aria-labelledby="visit-questions-title" className="space-y-5">
          <h2 id="visit-questions-title" className="text-[22px] font-semibold">진료실에서 여쭤볼 것</h2>
          <QuestionStatus
            card={data}
            editing={editing}
            regenerating={regenerate.isPending}
            regenerateFailed={regenerate.isError}
            onRegenerate={() => { setSaved(false); regenerate.mutate(); }}
          />
          {editing ? (
            <>
              {pendingAtEdit && data.generationStatus !== 'PENDING'
                ? <Notice role="status">정리안이 준비됐어요. 취소하시면 정리안을 보여드려요.</Notice>
                : null}
              <QuestionEditor
                items={items}
                onCancel={() => setEditing(false)}
                onSaved={() => { setEditing(false); setSaved(true); }}
              />
            </>
          ) : (
            <>
              {items.length === 0 ? (
                data.emptyMessage ? <p>{data.emptyMessage}</p> : null
              ) : (
                <ol className="space-y-5">
                  {items.map((item, i) => (
                    <li key={item.id} className="min-w-0"><QuestionItemView item={item} index={i} /></li>
                  ))}
                </ol>
              )}
              <Button
                variant="plain"
                onClick={() => {
                  setSaved(false);
                  setPendingAtEdit(data.generationStatus === 'PENDING');
                  setEditing(true);
                }}
              >
                {items.length === 0 ? '+ 질문 적기' : '질문 고치기'}
              </Button>
              {saved ? <Notice role="status">질문을 저장했습니다.</Notice> : null}
            </>
          )}
        </section>
      )}

      {data.therapistGlance.length > 0 ? (
        <section className="note-surface">
          <h2 className="font-semibold">진료실에서 보여드릴 요약</h2>
          <ul className="flex flex-col gap-2 pt-3">
            {data.therapistGlance.map((g) => <li key={g} className="text-ink-soft">{g}</li>)}
          </ul>
        </section>
      ) : null}

      <div className="pt-10">
        <TherapistLinkPanel />
      </div>
    </main>
  );
}
```

`useSaveExtra`는 다른 화면에서 쓰지 않으면 지운다(`grep -rn useSaveExtra frontend/src`).

- [x] **Step 4: 통과 확인**

Run: `npm --prefix frontend test && npm --prefix frontend run typecheck && npm --prefix frontend run build`
Expected: PASS. 다른 화면 테스트(예: 홈)가 `usePrepCard` 반환 형태에 의존해 깨지면 그 테스트의 표본에 새 필드를 넣어 고친다.

- [x] **Step 5: 인수인계 칸 갱신 후 커밋**

```bash
git add frontend docs/superpowers/plans/2026-09-18-caregiver-question-synthesis.md
git commit -m "feat: show one editable visit question list with notes as evidence"
```

---

### Task 10: 치료사 화면의 근거 주차

**Files:**
- Modify: `frontend/src/screens/Therapist.tsx`
- Test: `frontend/src/screens/Therapist.test.tsx`

**Interfaces:**
- Consumes: `TherapistSummary.questionDetails?` (Task 8)

- [ ] **Step 1: 실패하는 테스트 작성** (`Therapist.test.tsx`에 추가, 기존 표본을 복사해 `questionDetails`를 넣는다)

1. `questionDetails: [{ sentence: '합성 정리 질문인데 괜찮을까요?', origin: 'LLM', noteWeeks: [3, 5] }, { sentence: '직접 적은 합성 질문인데 괜찮을까요?', origin: 'CAREGIVER', noteWeeks: [] }]`, `freeNotes`에는 3주만 있을 때:
   - 두 문장이 한 번씩만 보인다(`questions`/`extraQuestions`와 겹쳐 두 번 나오지 않는다).
   - 첫 문장 아래에 `보호자 기록` 과 `3주` 버튼, 그리고 버튼이 아닌 글자 `5주`가 보인다.
   - `3주` 버튼을 누르면 `window.location.hash`가 바뀌지 않고, id가 `note-week-3`인 요소의 `scrollIntoView`가 불린다(`Element.prototype.scrollIntoView = vi.fn()`).
2. `questionDetails`가 없는 표본은 기존 테스트 그대로 통과한다.

- [ ] **Step 2: 실패 확인**

Run: `npm --prefix frontend test -- src/screens/Therapist.test.tsx`
Expected: FAIL.

- [ ] **Step 3: 구현**

원문 목록의 `<li key={n.week}>`에 `id={`note-week-${n.week}`}`와 `tabIndex={-1}`을 붙인다. 질문 구역을 이렇게 바꾼다.

```tsx
      {data.questionDetails ? (
        data.questionDetails.length > 0 ? (
          <section className="note-surface mt-6 min-w-0">
            <h2 className="font-semibold">보호자가 여쭤보고 싶은 것</h2>
            <ul className="list-disc pt-3 pl-5">
              {data.questionDetails.map((q, i) => (
                <li key={`${i}-${q.sentence}`}>
                  <p>{q.sentence}</p>
                  {q.noteWeeks.length > 0 ? (
                    <p className="text-small text-ink-soft">
                      보호자 기록{' '}
                      {q.noteWeeks.map((w, j) => (
                        <span key={w}>
                          {j > 0 ? '·' : ''}
                          {noteWeeks.has(w) ? (
                            <button type="button" className="underline" onClick={() => showNote(w)}>{w}주</button>
                          ) : `${w}주`}
                        </span>
                      ))}
                    </p>
                  ) : null}
                </li>
              ))}
            </ul>
          </section>
        ) : null
      ) : data.questions.length + data.extraQuestions.length > 0 ? (
        /* 옛 백엔드: 기존 구역 그대로 */
      ) : null}
```

마지막 분기에는 기존 `<section>…</section>`을 그대로 둔다. 컴포넌트 안에:

```tsx
  // 치료사 토큰이 URL fragment로 오므로 location.hash를 바꾸면 안 된다. 스크롤과 초점만 옮긴다.
  const noteWeeks = new Set(data.freeNotes.map((n) => n.week));
  const showNote = (week: number) => {
    const target = document.getElementById(`note-week-${week}`);
    target?.scrollIntoView({ block: 'start' });
    target?.focus();
  };
```

(`data`가 준비된 뒤의 위치에 둔다.)

- [ ] **Step 4: 통과 확인**

Run: `npm --prefix frontend test && npm --prefix frontend run typecheck`
Expected: PASS.

- [ ] **Step 5: 인수인계 칸 갱신 후 커밋**

```bash
git add frontend docs/superpowers/plans/2026-09-18-caregiver-question-synthesis.md
git commit -m "feat: link therapist questions to the caregiver weeks they came from"
```

---

### Task 11: 브라우저 테스트와 문서 정정

**Files:**
- Modify: `frontend/e2e/warm-observation.spec.ts`
- Modify (필요 시): `frontend/e2e/critical-flow.spec.ts`
- Modify: `README.md` (§1 "검증된 것", §8 첫 절, 운영 상태 표의 LLM 줄)
- Modify: `docs/superpowers/specs/2026-09-05-api-design.md`, `docs/superpowers/specs/2026-09-07-llm-server-design.md` (대체 표시 한 단락)

- [ ] **Step 1: 합성 UI 스펙 갱신**

`warm-observation.spec.ts`의 진료 준비 목 응답에 Task 9의 `base` 표본과 같은 새 필드를 넣는다. "긴 추가 질문 5개" 검사는 **편집 화면**으로 옮긴다: `질문 고치기` → 긴 합성 질문 5개를 `+ 질문 추가`로 넣고, 각 `질문 {n}` 칸의 `scrollHeight <= clientHeight + 1`(내부 세로 잘림 없음)과 페이지 가로 넘침 없음을 확인한다. 저장 요청은 `page.route`로 받아 200 카드로 응답한다.

- [ ] **Step 2: 실제 통합 흐름 확인**

Run: `bash scripts/ci/integration-e2e.sh` (Docker 필요. 꺼져 있으면 `open -a Docker` 후 데몬을 기다린다)
Expected: 통과. `critical-flow.spec.ts`가 옛 "내가 더 여쭤보고 싶은 것" UI나 문구에 의존해 실패하면, 같은 의미의 새 UI 조작(편집 → 추가 → 저장)으로 바꾼다. 데이터·토큰·캐시 검사는 줄이지 않는다. 통합 환경은 LLM이 꺼져 있으므로 기대 출처는 `관찰에서 나온 질문`이다.

- [ ] **Step 3: README 정정**

§1 "검증된 것"의 첫 두 문단(두 문장이 "너무 좋다"고 평가됐고 산출물 기준이라는 서술)을 다음으로 바꾼다. 두 예시 문장 인용은 "규칙 엔진 템플릿의 예"로 남긴다.

```markdown
물리치료사 인터뷰에서 좋은 평가를 받은 것은 특정 문장이 아니라 **구조**였습니다.
보호자가 기록할 때마다 자기 생각을 남기고, 진료 때 LLM이 그 생각들을 모아
"선생님께 무엇을 어떻게 여쭤보면 좋을지" 정리해 주는 구조입니다
(2026-09-17 제품 소유자 정정, `docs/superpowers/specs/2026-09-17-caregiver-question-synthesis-design.md`).

아래 두 문장은 규칙 엔진이 관찰 기록만으로 만드는 템플릿 질문의 예입니다.
LLM이 꺼져 있거나 보호자 기록이 없을 때 이 형태의 질문이 보입니다.
```

§8 "이 제품에서 LLM이 하는 일은 좁습니다"의 둘째·셋째 문단(`templateSentence`만 전달)을 다음으로 바꾼다.

```markdown
LLM은 **주차별 보호자 원문과 규칙 엔진이 찾은 관찰 변화**를 받아, 보호자가 선생님께 여쭤볼
질문을 최대 3개 정리합니다. 질문마다 근거(원문 주차, 관찰 변화)를 함께 내고, 코드 검증기가
근거·숫자·금지 표현을 확인합니다. 판정(무엇이 변했는지)은 여전히 규칙 엔진이 합니다.
원문은 노트북의 Ollama로만 가고 로그에 남지 않습니다.
```

같은 절의 "Qwen3는 기본으로 생각 모드가 켜져… 반드시 끕니다" 줄 뒤에 "끄는 방법은 `reasoning_effort` 설정값이며(`NEXTVISIT_LLM_REASONING_EFFORT`), `/no_think`는 Ollama OpenAI 호환 엔드포인트에서 무시된다"를 덧붙인다. 운영 상태 표의 LLM 줄은 Task 13에서 실측값으로 갱신하므로 여기서는 "2026-09-17 정리 기능으로 교체 중 — 운영 반영 전"으로 둔다.

- [ ] **Step 4: 옛 설계서 대체 표시**

두 설계서의 첫 제목 아래에 넣는다.

```markdown
> **LLM 입력과 검증 부분은 2026-09-17자 [보호자 기록 기반 질문 정리 설계](2026-09-17-caregiver-question-synthesis-design.md)로 대체됐다.** 보호자 원문을 LLM에 보내고, 문장 다듬기 검사 대신 근거 기반 검사를 쓴다.
```

- [ ] **Step 5: 전체 검증 후 커밋**

Run: `npm --prefix frontend test && npm --prefix frontend run typecheck && npm --prefix frontend run build && (cd backend && ./gradlew clean test)`
Expected: 모두 PASS.

```bash
git add frontend README.md docs docs/superpowers/plans/2026-09-18-caregiver-question-synthesis.md
git commit -m "docs: correct the LLM role and update browser checks for the question list"
```

---

### Task 12: 품질 비교 — 생각 모드 끔/켬 (제품 소유자 판단 필요)

**Files:**
- Create: `backend/api/src/test/resources/synthesis-fixtures/case-a.json`, `case-b.json`, `case-c.json`
- Create: `backend/api/src/test/java/nextvisit/api/llm/LiveSynthesisEvaluation.java`
- Modify: `backend/api/build.gradle.kts`
- Create: `docs/qa/2026-09-18-synthesis-quality.md`

- [ ] **Step 1: 실측 테스트를 기본 실행에서 분리**

`build.gradle.kts`:

```kotlin
tasks.test {
    useJUnitPlatform {
        excludeTags("live-llm")
    }
    testLogging {
        events("passed", "failed", "skipped")
    }
}

tasks.register<Test>("liveLlmTest") {
    description = "Runs synthesis against a real Ollama. Needs -Dnextvisit.live.baseUrl."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("live-llm") }
    systemProperty("nextvisit.live.baseUrl", System.getProperty("nextvisit.live.baseUrl") ?: "")
    systemProperty("nextvisit.live.effort", System.getProperty("nextvisit.live.effort") ?: "none")
    systemProperty("nextvisit.live.maxTokens", System.getProperty("nextvisit.live.maxTokens") ?: "3000")
    outputs.upToDateWhen { false }
}
```

- [ ] **Step 2: 합성 fixture 작성**

각 파일 형태:

```json
{
  "templates": [
    {"rank": 1, "type": "STALL", "items": ["ambulation"], "sentence": "집 안에서 걷기는 6주째 그대로입니다. 걷기는 왜 안 늘고 있을까요?"}
  ],
  "weeks": {
    "1": {"freeNote": {"text": "…", "timeTag": "MORNING"}, "itemNotes": {"ambulation": "…"}},
    "2": {"freeNote": null, "itemNotes": {}}
  }
}
```

- `case-a`: 걷기 정체(템플릿 1개) + 6주 중 4주에 "현관 문턱에서 망설이신다", "계단 앞에서 손잡이를 찾으신다" 같은 걱정.
- `case-b`: 템플릿 2개(화장실 변화, 일어설 때 찡그림) + 저녁마다 피곤해하신다는 기록 3주, 숫자가 들어간 기록 1주("밤에 2번 깨셨다").
- `case-c`: 템플릿 0개 + 원문만 5주(식사 속도, 숟가락 쥐는 손, 말수).

모든 문장은 새로 지은 합성 문장이다. 데모 시드(`DemoSeedWriter`)나 실제 보호자 문장을 쓰지 않는다. 각 주의 글자 수는 20~120자.

- [ ] **Step 3: 실측 하네스 작성**

`LiveSynthesisEvaluation`은 `@Tag("live-llm")` 테스트다. 각 fixture를 `SnapshotBody`로 바꿔 `SynthesisInputAssembler` → `QuestionSynthesisPrompt` → 실제 `OpenAiCompatibleLlmClient`(baseUrl, effort, maxTokens는 시스템 속성) → `SynthesisValidator` 순으로 **케이스당 3회** 돌린다. `baseUrl`이 비어 있으면 `Assumptions.abort`로 건너뛴다. 읽기 제한은 300초로 둔다.

결과를 `backend/api/build/live-llm/results-<effort>.json`에 쓴다: 실행마다 `case`, `run`, `elapsedMs`, `accepted`(bool), `rule`(거부 규칙 또는 null), `questions`(통과한 문장·근거). **합성 데이터이므로** 문장을 결과 파일에 남겨도 된다. 로그에는 문장을 찍지 않는다.

- [ ] **Step 4: 노트북 Ollama에 읽기 전용으로 실행**

```bash
ssh -N -L 11435:127.0.0.1:11434 llm &   # 조회용 포트 포워딩, 끝나면 kill
cd backend
./gradlew :api:liveLlmTest -Dnextvisit.live.baseUrl=http://127.0.0.1:11435/v1 -Dnextvisit.live.effort=none -Dnextvisit.live.maxTokens=3000
./gradlew :api:liveLlmTest -Dnextvisit.live.baseUrl=http://127.0.0.1:11435/v1 -Dnextvisit.live.effort= -Dnextvisit.live.maxTokens=6000
```

운영 컨테이너를 재시작하거나 모델을 받지 않는다. 운영 API가 같은 GPU를 쓰므로 실행 전후 `curl -s https://api.byguardian.site/health`로 정상인지 확인한다.

- [ ] **Step 5: 결과 문서 작성**

`docs/qa/2026-09-18-synthesis-quality.md`(한국어)에 다음을 적는다.
- 방식별·케이스별 통과 수(3회 중), 시도별 소요 시간(최소/중앙/최대), 거부 규칙 분포
- **케이스별 통과한 질문 세트 전문**(합성) — 제품 소유자가 읽고 판단할 수 있게
- 권고: 기본 `reasoning-effort`, `max-output-tokens`, `read-timeout` 값과 근거
- 한계: 결정적 설정(seed 0)이라 3회는 재현성이지 채택률이 아님, 합성 데이터, 표본 3개

- [ ] **Step 6: 커밋하고 멈춘다**

인수인계 칸을 "현재 위치: Task 12 측정 완료, 제품 소유자 판단 대기 / 다음 할 일: 소유자가 `docs/qa/2026-09-18-synthesis-quality.md`의 질문 세트를 보고 기본값을 정하면, `application.yml`의 `reasoning-effort`·`max-output-tokens` 기본값과 `infra/demo/compose.demo.yml`의 기본값을 그 값으로 바꾸고 Task 13"으로 갱신한다.

```bash
git add backend docs docs/superpowers/plans/2026-09-18-caregiver-question-synthesis.md
git commit -m "test: measure synthesis quality with reasoning on and off against the laptop model"
```

**여기서 멈추고 제품 소유자의 판단을 받는다.**

---

### Task 13: 운영 반영과 인수 (제품 소유자가 sudo로 실행)

- [ ] **Step 1: jar 빌드**

```bash
cd backend && ./gradlew :api:bootJar && ls -l api/build/libs/api.jar
```

- [ ] **Step 2: 소유자에게 전달할 명령** (Mac 터미널 → 노트북 순서)

```bash
# Mac
scp backend/api/build/libs/api.jar llm:/tmp/api.jar

# 노트북 (ssh -t llm)
sudo cp /home/nextvisit-runner/demo/api.jar /home/nextvisit-runner/demo/api.jar.bak-$(date +%Y%m%d)
sudo install -o nextvisit-runner -g nextvisit-runner -m 0644 /tmp/api.jar /home/nextvisit-runner/demo/api.jar
# compose.demo.yml에 NEXTVISIT_LLM_REASONING_EFFORT 줄이 없으면 READ_TIMEOUT 줄 아래에 추가
sudo grep -n 'NEXTVISIT_LLM_REASONING_EFFORT' /home/nextvisit-runner/demo/compose.demo.yml || \
  sudo sed -i 's|^\( *\)NEXTVISIT_LLM_READ_TIMEOUT: \(.*\)$|\1NEXTVISIT_LLM_READ_TIMEOUT: \2\n\1NEXTVISIT_LLM_REASONING_EFFORT: "${NEXTVISIT_LLM_REASONING_EFFORT:-none}"|' \
  /home/nextvisit-runner/demo/compose.demo.yml
cd /tmp
sudo -u nextvisit-runner docker compose -f /home/nextvisit-runner/demo/compose.demo.yml \
  --project-directory /home/nextvisit-runner/demo build api
sudo -u nextvisit-runner docker compose -f /home/nextvisit-runner/demo/compose.demo.yml \
  --project-directory /home/nextvisit-runner/demo up -d --no-deps api
docker ps --format '{{.Names}} {{.Status}}'
```

Flyway가 V3를 적용하므로 재기동 전에 Task 5(이전 계획)의 백업 스크립트로 덤프를 한 번 받는다: `/home/milo/nextvisit-backups/pg-backup.sh nextvisit-demo-postgres-1 /home/milo/nextvisit-backups/out`.

- [ ] **Step 3: 운영 API 확인 (읽기와 합성 데모만)**

```bash
curl -s https://api.byguardian.site/health
```

합성 데모 케이스를 만들고 `GET /me/prep-card`에 `items`, `generationStatus`, `edited`, `suggestionAvailable`이 있는지, 원문이 있는 주를 저장한 뒤 로그에 `code=SUCCESS`가 나오고 `items[0].origin`이 `LLM`인지 확인한다(`ssh llm 'docker logs --since 10m nextvisit-demo-api-1 | grep "llm generation result"'`).

- [ ] **Step 4: 프런트 반영**

PR을 올리고(백엔드가 이미 운영에 있으므로 순서가 맞다) 병합 후 `https://app.byguardian.site/build.json`의 커밋이 병합 커밋인지 확인한다. 병합은 제품 소유자 승인 뒤에만 한다.

- [ ] **Step 5: 운영 전체 흐름**

Playwright로 `https://app.byguardian.site`에서(합성 데모 케이스, 375px·1280px): 원문이 있는 기록 저장 → "정리하고 있어요" 안내 → 정리 완료 → 근거 펼치기에 원문 → 편집·저장 → 새 기록 저장 → "새 기록이 반영된 정리안" → 다시 정리하기 → 치료사 화면의 "보호자 기록 N주" 버튼으로 원문 이동(주소의 `#`가 바뀌지 않음).

- [ ] **Step 6: 인수 문서와 마무리**

`docs/qa/2026-09-18-synthesis-acceptance.md`에 위 결과, 실측 소요 시간, 채택 수, 남은 한계를 적고, README 운영 상태 표의 LLM 줄을 실측값으로 갱신한다. 인수인계 칸을 "완료"로 바꾸고 커밋한다.

---

## Self-Review

- **설계서 대응:** 1절 결정 → Task 1~10. 3.1 입력 → Task 2. 3.2 지시 → Task 4. 3.3 검증 → Task 3. 3.4 설정 → Task 1, 12. 3.5 템플릿 경로 → Task 5(원문 없으면 호출 안 함). 4.1~4.3 → Task 5, 6, 7. 4.4 배포 순서 → Task 13. 5.1 → Task 8, 9. 5.2 → Task 10. 6.1 → Task 2~7 테스트. 6.2 → Task 12. 6.3 → Task 8~11. 6.4 → Task 13. 7절 문서 → Task 11, 인수인계 칸. 10절 열린 결정 → 인수인계 칸, Task 12.
- **이름 일관성:** `LlmPrompt`, `SynthesisInput(.Detection/.NoteLine)`, `SynthesisInputAssembler.assemble/noteLines/hasNote`, `SynthesisValidator(.Question/.Accepted/.Rule/.Rejected)`, `QuestionSynthesisPrompt.build`, `QuestionCacheBody.Q.template/originOrDefault/basisOrEmpty`, `QuestionCacheBody.Basis`, `ConfirmedItem.caregiver/isCaregiver`, `QuestionListService.visible/suggestionAvailable/generationStatus/save/regenerate/replaceCaregiver`, `SaveQuestionsRequest.Item`, `PrepCardDto.Item/ItemBasis/NoteBasis`, `TherapistSummaryDto.QuestionDetail`, `nextPrepPoll`, `useSaveQuestions`, `useRegenerateQuestions`.
- **알려진 한계:** 정리 질문의 관찰 근거 표는 근거 변화의 항목을 도움 수준 축으로만 보여준다(`axesFor`의 기본값). 편집 중 정리안이 완성되면 옛 id가 맞지 않아 저장 시 "직접 적은 질문"으로 남는다 — 근거를 지어내지 않기 위한 선택이다.
