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
                LlmPrompt prompt = prompts.build(templates.questions(),
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
