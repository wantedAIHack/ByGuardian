package nextvisit.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import nextvisit.api.auth.Guardian;
import nextvisit.api.auth.GuardianRepository;
import nextvisit.api.cases.CaseEntity;
import nextvisit.api.cases.CaseRepository;
import nextvisit.api.cases.Diagnosis;
import nextvisit.api.cases.PareticSide;
import nextvisit.api.cases.VerbalDifficulty;
import nextvisit.api.questions.QuestionCache;
import nextvisit.api.questions.QuestionCacheRepository;
import nextvisit.api.questions.QuestionCacheStatus;
import nextvisit.api.snapshots.Snapshot;
import nextvisit.api.snapshots.SnapshotKind;
import nextvisit.api.snapshots.SnapshotRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PersistenceTest {

    @Autowired CaseRepository cases;
    @Autowired GuardianRepository guardians;
    @Autowired SnapshotRepository snapshots;
    @Autowired QuestionCacheRepository caches;
    @Autowired ObjectMapper mapper;

    private CaseEntity newCase(String recoveryHash) {
        return cases.save(new CaseEntity("stroke", LocalDate.of(2026, 9, 5), Diagnosis.STROKE, PareticSide.RIGHT,
            VerbalDifficulty.OFTEN, LocalDate.of(2026, 9, 30), recoveryHash, Instant.parse("2026-09-05T01:00:00Z")));
    }

    @Test
    void jsonColumnRoundTripsOnThisDatabase() throws Exception {
        CaseEntity kase = newCase("rc-" + UUID.randomUUID());
        Guardian g = guardians.save(new Guardian(kase.getId(), "딸", "tok-" + UUID.randomUUID(), Instant.now()));
        String body = "{\"items\":{\"toilet\":{\"level\":{\"value\":2,\"source\":\"CONFIRMED\"}}},\"painSignal\":{},\"sleep\":null,\"freeNote\":null}";
        Snapshot s = snapshots.save(new Snapshot(kase.getId(), 1, SnapshotKind.BASELINE, false, g.getId(), body, Instant.now()));

        Snapshot loaded = snapshots.findByCaseIdAndWeek(kase.getId(), 1).orElseThrow();
        assertEquals(mapper.readTree(body), mapper.readTree(loaded.getBody()));
        assertEquals(SnapshotKind.BASELINE, loaded.getKind());
        assertEquals(1, snapshots.findByCaseIdOrderByWeekAsc(kase.getId()).size());
    }

    @Test
    void caseFlagsFollowSpec() {
        CaseEntity kase = newCase("rc-" + UUID.randomUUID());
        assertTrue(kase.handEnabled());
        assertTrue(kase.signalsEnabled());
        assertEquals("[]", kase.getExtraQuestions());
        assertTrue(cases.findByRecoveryCodeHash(kase.getRecoveryCodeHash()).isPresent());

        CaseEntity other = cases.save(new CaseEntity("stroke", LocalDate.of(2026, 9, 5), Diagnosis.UNKNOWN, PareticSide.UNKNOWN,
            VerbalDifficulty.NONE, null, "rc-" + UUID.randomUUID(), Instant.now()));
        assertTrue(!other.handEnabled());
        assertTrue(!other.signalsEnabled());
    }

    @Test
    void latestSnapshotQueries() {
        CaseEntity kase = newCase("rc-" + UUID.randomUUID());
        Guardian g = guardians.save(new Guardian(kase.getId(), "딸", "tok-" + UUID.randomUUID(), Instant.now()));
        for (int w : new int[] {1, 2, 4}) {
            snapshots.save(new Snapshot(kase.getId(), w, w == 1 ? SnapshotKind.BASELINE : SnapshotKind.WEEKLY, w == 2, g.getId(), "{}", Instant.now()));
        }
        assertEquals(4, snapshots.findFirstByCaseIdOrderByWeekDesc(kase.getId()).orElseThrow().getWeek());
        assertEquals(2, snapshots.findFirstByCaseIdAndWeekLessThanOrderByWeekDesc(kase.getId(), 4).orElseThrow().getWeek());
        assertEquals(2, snapshots.findFirstByCaseIdAndWeekLessThanOrderByWeekDesc(kase.getId(), 3).orElseThrow().getWeek());
    }

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
}
