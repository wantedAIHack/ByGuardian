package nextvisit.api.questions;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import nextvisit.api.cases.CaseEntity;
import nextvisit.api.cases.CaseRepository;
import nextvisit.api.common.Json;
import nextvisit.api.common.NotFoundException;
import nextvisit.api.engine.EngineBridge;
import nextvisit.api.llm.LlmProperties;
import nextvisit.api.llm.QuestionGenerationRequested;
import nextvisit.api.llm.SynthesisInputAssembler;
import nextvisit.api.snapshots.Snapshot;
import nextvisit.api.snapshots.SnapshotBody;
import nextvisit.api.snapshots.SnapshotRepository;
import nextvisit.engine.Detection;
import nextvisit.engine.PipelineResult;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** README §8: 저장할 때마다 안전한 템플릿 질문을 먼저 만들어 캐시한다. */
@Service
@Transactional
public class QuestionService {

    private final CaseRepository cases;
    private final SnapshotRepository snapshots;
    private final QuestionCacheRepository caches;
    private final EngineBridge bridge;
    private final Json json;
    private final Clock clock;
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

    public QuestionCacheBody refresh(UUID caseId) {
        // Lock order is case -> snapshots -> cache for every refresh of one case.
        CaseEntity kase = cases.findByIdForQuestionRefresh(caseId)
            .orElseThrow(() -> new NotFoundException("케이스가 없습니다"));
        List<Snapshot> snaps = snapshots.findByCaseIdOrderByWeekAsc(caseId);
        PipelineResult r = bridge.run(kase, snaps);

        List<QuestionCacheBody.Q> qs = new ArrayList<>();
        for (int i = 0; i < r.selected().size(); i++) {
            Detection d = r.selected().get(i);
            String template = r.sentences().get(i);
            QuestionCacheBody.SignalRef ref = d.signal() == null ? null
                : new QuestionCacheBody.SignalRef(d.signal().action().name(), d.signal().kind().name());
            qs.add(QuestionCacheBody.Q.template(i + 1, d.type().name(), d.items(), ref, template));
        }
        QuestionCacheBody body = new QuestionCacheBody(List.copyOf(qs), r.detections().size());

        int week = snaps.isEmpty() ? 0 : snaps.get(snaps.size() - 1).getWeek();
        String js = json.toJson(body);
        Instant now = Instant.now(clock);
        // 2026-09-17 정리 설계 3.1: 보호자 원문이 하나도 없으면 LLM을 부르지 않는다.
        boolean generateWithLlm = properties.enabled() && snaps.stream().anyMatch(this::hasNote);
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
    }

    private boolean hasNote(Snapshot snapshot) {
        return SynthesisInputAssembler.hasNote(json.fromJson(snapshot.getBody(), SnapshotBody.class));
    }

    @Transactional(readOnly = true)
    public Optional<QuestionCacheBody> current(UUID caseId) {
        return caches.findByCaseId(caseId).map(c -> json.fromJson(c.getBody(), QuestionCacheBody.class));
    }
}
