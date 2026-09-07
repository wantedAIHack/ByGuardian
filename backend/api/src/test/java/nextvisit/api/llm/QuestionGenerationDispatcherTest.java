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
