package nextvisit.api.llm;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import nextvisit.api.common.Json;
import nextvisit.api.questions.QuestionCache;
import nextvisit.api.questions.QuestionCacheBody;
import nextvisit.api.questions.QuestionCacheRepository;
import nextvisit.api.questions.QuestionCacheStatus;
import nextvisit.api.snapshots.Snapshot;
import nextvisit.api.snapshots.SnapshotBody;
import nextvisit.api.snapshots.SnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "nextvisit.llm.enabled", havingValue = "true")
public class QuestionGenerationCoordinator {

    private static final Logger log = LoggerFactory.getLogger(QuestionGenerationCoordinator.class);

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

    private static long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
}
