package nextvisit.api.demo;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import nextvisit.api.auth.Guardian;
import nextvisit.api.cases.CaseEntity;
import nextvisit.api.common.Json;
import nextvisit.api.common.WeekCalculator;
import nextvisit.api.engine.EngineBridge;
import nextvisit.api.snapshots.Snapshot;
import nextvisit.api.snapshots.SnapshotBody;
import nextvisit.api.snapshots.SnapshotKind;
import nextvisit.api.snapshots.SnapshotRepository;
import nextvisit.engine.WeekRecord;
import nextvisit.engine.demo.DemoSeed;
import org.springframework.stereotype.Component;

/**
 * README §10 시드를 6개 스냅샷으로 기록한다. 케이스는 마비 쪽이 LEFT/RIGHT이고 비언어 관찰이 활성이어야
 * 시드의 hand 축과 통증 신호가 엔진까지 도달한다. 같은 주가 있으면 덮어쓴다(온보딩의 1주차 기준선 포함).
 */
@Component
public class DemoSeedWriter {

    /** README §10 자유 기록. 주차 → 원문. */
    public static final Map<Integer, String> FREE_NOTES = Map.of(
        3, "오후만 되면 오른쪽 어깨를 자꾸 만지신다",
        4, "오후에 어깨를 만지고 계셔서 물어봐도 대답을 안 하신다",
        6, "낮잠 자고 일어나면 어깨 쪽을 감싸신다");

    /**
     * README §5·§9의 야간 수면(3단계, 매주 필수 관찰). 평범하고 튀지 않는 값으로 채운다 —
     * 이 시드는 심사위원이 /t/:token에서 직접 보는 화면이라, 전부 비어 있으면(과거엔 그랬다)
     * 실제로는 매주 걷는 관찰인 '야간 수면' 구역 자체가 렌더되지 않는다.
     */
    public static final Map<Integer, Integer> SLEEP = Map.of(
        1, 1,
        2, 1,
        3, 2,
        4, 1,
        5, 1,
        6, 2);

    private final SnapshotRepository snapshots;
    private final EngineBridge bridge;
    private final WeekCalculator weeks;
    private final Json json;
    private final Clock clock;

    public DemoSeedWriter(SnapshotRepository snapshots, EngineBridge bridge, WeekCalculator weeks, Json json, Clock clock) {
        this.snapshots = snapshots;
        this.bridge = bridge;
        this.weeks = weeks;
        this.json = json;
        this.clock = clock;
    }

    public List<Snapshot> write(CaseEntity kase, Guardian author) {
        List<Snapshot> out = new ArrayList<>();
        for (WeekRecord w : DemoSeed.stroke().toWeeks()) {
            SnapshotBody body = bridge.toBody(w, SLEEP.get(w.week()), FREE_NOTES.get(w.week()));
            SnapshotKind kind = w.week() == 1 ? SnapshotKind.BASELINE
                : weeks.isFullRecheck(w.week()) ? SnapshotKind.FULL_RECHECK : SnapshotKind.WEEKLY;
            boolean noChange = w.week() == DemoSeed.CARRIED_WEEK;
            String js = json.toJson(body);
            Instant at = Instant.now(clock);
            Snapshot s = snapshots.findByCaseIdAndWeek(kase.getId(), w.week())
                .map(existing -> {
                    existing.overwrite(kind, noChange, author.getId(), js, at);
                    return existing;
                })
                .orElseGet(() -> new Snapshot(kase.getId(), w.week(), kind, noChange, author.getId(), js, at));
            out.add(snapshots.save(s));
        }
        return out;
    }
}
