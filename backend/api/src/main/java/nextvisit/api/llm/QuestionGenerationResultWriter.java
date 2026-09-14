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
