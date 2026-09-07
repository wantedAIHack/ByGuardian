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
