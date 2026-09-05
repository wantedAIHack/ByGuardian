package nextvisit.api.snapshots;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import nextvisit.api.auth.AuthContext;
import nextvisit.api.cases.CaseEntity;
import nextvisit.api.common.ConflictException;
import nextvisit.api.common.Json;
import nextvisit.api.common.ValidationException;
import nextvisit.api.common.WeekCalculator;
import nextvisit.api.questions.QuestionService;
import nextvisit.engine.ObservationSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 설계 3절 주차 규칙과 복사 규칙. 저장 직후 질문 캐시를 갱신한다(README §8). */
@Service
@Transactional
public class SnapshotService {

    private final SnapshotRepository snapshots;
    private final SnapshotAssembler assembler;
    private final QuestionService questions;
    private final WeekCalculator weeks;
    private final Json json;
    private final Clock clock;

    public SnapshotService(SnapshotRepository snapshots, SnapshotAssembler assembler, QuestionService questions,
                           WeekCalculator weeks, Json json, Clock clock) {
        this.snapshots = snapshots;
        this.assembler = assembler;
        this.questions = questions;
        this.weeks = weeks;
        this.json = json;
        this.clock = clock;
    }

    public WeeklyRecordResponse saveWeekly(AuthContext ctx, int week, WeeklyRecordRequest req) {
        CaseEntity kase = ctx.kase();
        int current = weeks.currentWeek(kase.getStartDate());
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

        Snapshot previous = snapshots.findFirstByCaseIdAndWeekLessThanOrderByWeekDesc(kase.getId(), week)
            .orElseThrow(() -> new ConflictException("BASELINE_ONLY", "기준선이 없습니다"));
        SnapshotBody prevBody = json.fromJson(previous.getBody(), SnapshotBody.class);
        SnapshotBody body = assembler.build(kase, changed, prevBody, req.painSignal(), req.sleep(), req.freeNote());

        SnapshotKind kind = fullRecheck ? SnapshotKind.FULL_RECHECK : SnapshotKind.WEEKLY;
        String js = json.toJson(body);
        Instant now = Instant.now(clock);
        UUID author = ctx.guardian().getId();
        Snapshot s = snapshots.findByCaseIdAndWeek(kase.getId(), week)
            .map(existing -> {
                existing.overwrite(kind, req.noChange(), author, js, now);
                return existing;
            })
            .orElseGet(() -> new Snapshot(kase.getId(), week, kind, req.noChange(), author, js, now));
        snapshots.save(s);

        questions.refresh(kase.getId());
        return new WeeklyRecordResponse(week, kind, true);
    }
}
