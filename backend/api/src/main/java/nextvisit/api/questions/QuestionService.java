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
import nextvisit.api.snapshots.Snapshot;
import nextvisit.api.snapshots.SnapshotRepository;
import nextvisit.engine.Detection;
import nextvisit.engine.PipelineResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** README §8: 저장할 때마다 질문을 만들어 캐시한다. 이 계획에서는 템플릿 문장으로 채운다(설계 6.2). */
@Service
@Transactional
public class QuestionService {

    private final CaseRepository cases;
    private final SnapshotRepository snapshots;
    private final QuestionCacheRepository caches;
    private final EngineBridge bridge;
    private final Json json;
    private final Clock clock;

    public QuestionService(CaseRepository cases, SnapshotRepository snapshots, QuestionCacheRepository caches,
                           EngineBridge bridge, Json json, Clock clock) {
        this.cases = cases;
        this.snapshots = snapshots;
        this.caches = caches;
        this.bridge = bridge;
        this.json = json;
        this.clock = clock;
    }

    public QuestionCacheBody refresh(UUID caseId) {
        CaseEntity kase = cases.findById(caseId).orElseThrow(() -> new NotFoundException("케이스가 없습니다"));
        List<Snapshot> snaps = snapshots.findByCaseIdOrderByWeekAsc(caseId);
        PipelineResult r = bridge.run(kase, snaps);

        List<QuestionCacheBody.Q> qs = new ArrayList<>();
        for (int i = 0; i < r.selected().size(); i++) {
            Detection d = r.selected().get(i);
            String template = r.sentences().get(i);
            QuestionCacheBody.SignalRef ref = d.signal() == null ? null
                : new QuestionCacheBody.SignalRef(d.signal().action().name(), d.signal().kind().name());
            qs.add(new QuestionCacheBody.Q(i + 1, d.type().name(), d.items(), ref, template, template, QuestionCacheBody.SOURCE_TEMPLATE));
        }
        QuestionCacheBody body = new QuestionCacheBody(List.copyOf(qs), r.detections().size());

        int week = snaps.isEmpty() ? 0 : snaps.get(snaps.size() - 1).getWeek();
        String js = json.toJson(body);
        Instant now = Instant.now(clock);
        UUID generationId = UUID.randomUUID();
        Optional<QuestionCache> existing = caches.findByCaseId(caseId);
        if (existing.isPresent()) {
            existing.get().update(week, QuestionCacheStatus.READY, generationId, js, now);
            caches.save(existing.get());
        } else {
            caches.save(new QuestionCache(caseId, week, QuestionCacheStatus.READY,
                generationId, js, now));
        }
        return body;
    }

    @Transactional(readOnly = true)
    public Optional<QuestionCacheBody> current(UUID caseId) {
        return caches.findByCaseId(caseId).map(c -> json.fromJson(c.getBody(), QuestionCacheBody.class));
    }
}
