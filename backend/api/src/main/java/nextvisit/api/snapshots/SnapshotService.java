package nextvisit.api.snapshots;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import nextvisit.api.auth.AuthContext;
import nextvisit.api.cases.CaseEntity;
import nextvisit.api.cases.CaseRepository;
import nextvisit.api.common.ConflictException;
import nextvisit.api.common.CaseTimeline;
import nextvisit.api.common.Json;
import nextvisit.api.common.ValidationException;
import nextvisit.api.common.WeekCalculator;
import nextvisit.engine.ObservationSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설계 3절 주차 규칙과 복사 규칙. 질문 캐시 갱신은 여기서 하지 않는다 — 실패해도 이 기록은 커밋돼야
 * 하므로 컨트롤러가 별도로, 실패를 삼키며 갱신한다(README §4 층 1).
 */
@Service
@Transactional
public class SnapshotService {

    private final SnapshotRepository snapshots;
    private final CaseRepository cases;
    private final SnapshotAssembler assembler;
    private final WeekCalculator weeks;
    private final CaseTimeline timeline;
    private final Json json;
    private final Clock clock;

    public SnapshotService(SnapshotRepository snapshots, CaseRepository cases, SnapshotAssembler assembler,
                           WeekCalculator weeks, CaseTimeline timeline, Json json, Clock clock) {
        this.snapshots = snapshots;
        this.cases = cases;
        this.assembler = assembler;
        this.weeks = weeks;
        this.timeline = timeline;
        this.json = json;
        this.clock = clock;
    }

    public WeeklyRecordResponse saveWeekly(AuthContext ctx, int week, WeeklyRecordRequest req) {
        CaseEntity kase = ctx.kase();
        int current = timeline.currentWeek(kase);
        if (week < 2) {
            throw new ConflictException("BASELINE_ONLY", "1주차는 온보딩의 기준선입니다. 주간 기록은 2주차부터입니다");
        }
        if (week != current) {
            throw new ConflictException("WEEK_MISMATCH", "이번 주는 " + current + "주차입니다");
        }
        boolean fullRecheck = weeks.isFullRecheck(week);
        Map<String, ItemInput> changed = req.changedItems() == null ? Map.of() : req.changedItems();
        if (req.noChange() && !changed.isEmpty()) {
            throw new ValidationException("noChange가 true면 changedItems는 비어 있어야 합니다");
        }
        if (!req.noChange() && changed.isEmpty()) {
            throw new ValidationException("noChange가 false면 changedItems가 하나 이상 필요합니다");
        }
        int total = ObservationSet.STROKE.items().size();
        if (fullRecheck) {
            if (req.noChange()) {
                throw new ValidationException(week + "주차는 전체 재확인 주입니다. " + total + "항목을 전부 보내주세요");
            }
            if (changed.size() != total) {
                throw new ValidationException("전체 재확인 주에는 " + total + "항목이 전부 필요합니다 (" + changed.size() + "개 받음)");
            }
        }

        // Serialize writes even when this week's row does not exist yet. An older client
        // must validate against the version committed by a concurrent questionnaire upgrade.
        cases.findByIdForQuestionRefresh(kase.getId()).orElseThrow();
        Snapshot previous = snapshots.findFirstByCaseIdAndWeekLessThanOrderByWeekDesc(kase.getId(), week)
            .orElseThrow(() -> new ConflictException("BASELINE_ONLY", "기준선이 없습니다"));
        // Same-week corrections carry from the saved snapshot, so they cannot silently
        // restore an earlier questionnaire version or lose already corrected answers.
        var existingSnapshot = snapshots.findByCaseIdAndWeek(kase.getId(), week);
        SnapshotBody prevBody = json.fromJson(existingSnapshot.orElse(previous).getBody(), SnapshotBody.class);
        SnapshotBody body = assembler.build(kase, changed, prevBody, req.painSignal(), req.sleep(), req.freeNote());
        if (existingSnapshot.isPresent()) {
            // Omitted items in a correction still belong to this week. Preserve their
            // original confirmation and note; only a new week carries values forward.
            Map<String, SnapshotBody.ItemValues> items = new LinkedHashMap<>(body.items());
            prevBody.items().forEach((code, values) -> {
                if (!changed.containsKey(code)) items.put(code, values);
            });
            body = new SnapshotBody(items, body.painSignal(), body.sleep(), body.freeNote());
        }

        SnapshotKind kind = fullRecheck ? SnapshotKind.FULL_RECHECK : SnapshotKind.WEEKLY;
        String js = json.toJson(body);
        Instant now = Instant.now(clock);
        UUID author = ctx.guardian().getId();
        Snapshot s = existingSnapshot
            .map(existing -> {
                existing.overwrite(kind, req.noChange(), author, js, now);
                return existing;
            })
            .orElseGet(() -> new Snapshot(kase.getId(), week, kind, req.noChange(), author, js, now));
        snapshots.save(s);

        return new WeeklyRecordResponse(week, kind, false);
    }
}
