package nextvisit.api.snapshots;

import static nextvisit.api.ApiTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import nextvisit.api.MutableClock;
import nextvisit.api.TestClockConfig;
import nextvisit.api.cases.CaseEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuestionnaireConcurrencyTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired MutableClock clock;
    @MockitoSpyBean SnapshotAssembler assembler;

    @Test void overlappingLegacyWriteWaitsForUpgradeThenRejectsDowngrade() throws Exception {
        clock.set(TestClockConfig.DEFAULT_TODAY);
        var onboarded = onboardDefault(mvc, mapper);
        clock.advanceDays(7);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                if (!releaseFirst.await(5, TimeUnit.SECONDS)) throw new AssertionError("upgrade was not released");
            } else secondEntered.countDown();
            return invocation.callRealMethod();
        }).when(assembler).build(any(CaseEntity.class), anyMap(), any(SnapshotBody.class), anyMap(), isNull(), isNull());
        var upgrade = Map.of("noChange", false, "changedItems", Map.of("dressing", Map.of(
            "questionnaireVersion", 2, "answers", Map.of("assistance", List.of("4")))), "painSignal", Map.of());
        var legacy = Map.of("noChange", false, "changedItems", Map.of("dressing", baselineItems(true).get("dressing")), "painSignal", Map.of());
        ExecutorService requests = Executors.newFixedThreadPool(2);
        try {
            var first = requests.submit(() -> mvc.perform(putJson(onboarded.token(), "/me/weeks/2", mapper, upgrade)).andReturn());
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS));
            var second = requests.submit(() -> mvc.perform(putJson(onboarded.token(), "/me/weeks/2", mapper, legacy)).andReturn());
            assertFalse(secondEntered.await(300, TimeUnit.MILLISECONDS), "second write must wait before reading the schema");
            releaseFirst.countDown();
            assertEquals(200, first.get(5, TimeUnit.SECONDS).getResponse().getStatus());
            assertEquals(400, second.get(5, TimeUnit.SECONDS).getResponse().getStatus());
        } finally {
            releaseFirst.countDown();
            requests.shutdownNow();
            assertTrue(requests.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
