package nextvisit.api.progress;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import nextvisit.api.auth.AuthContext;
import nextvisit.api.cases.CaseEntity;
import nextvisit.api.common.WeekCalculator;
import nextvisit.api.engine.EngineBridge;
import nextvisit.api.questions.QuestionCacheBody;
import nextvisit.api.questions.QuestionService;
import nextvisit.api.snapshots.Snapshot;
import nextvisit.api.snapshots.SnapshotRepository;
import nextvisit.engine.Axis;
import nextvisit.engine.Item;
import nextvisit.engine.ItemVerdicts;
import nextvisit.engine.Josa;
import nextvisit.engine.Labels;
import nextvisit.engine.ObservationSet;
import nextvisit.engine.PipelineResult;
import nextvisit.engine.Status;
import nextvisit.engine.Verdict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 층 2. 침묵 게이트는 여기서만 작동한다(README §4). 판정은 엔진, 여기서는 고르고 문구를 붙일 뿐. */
@Service
@Transactional(readOnly = true)
public class ProgressService {

    private final SnapshotRepository snapshots;
    private final EngineBridge bridge;
    private final QuestionService questions;
    private final TrajectoryMapper trajectories;
    private final WeekCalculator weeks;

    public ProgressService(SnapshotRepository snapshots, EngineBridge bridge, QuestionService questions,
                           TrajectoryMapper trajectories, WeekCalculator weeks) {
        this.snapshots = snapshots;
        this.bridge = bridge;
        this.questions = questions;
        this.trajectories = trajectories;
        this.weeks = weeks;
    }

    public ProgressDto progress(AuthContext ctx) {
        CaseEntity kase = ctx.kase();
        List<Snapshot> snaps = snapshots.findByCaseIdOrderByWeekAsc(kase.getId());
        int week = weeks.currentWeek(kase.getStartDate());
        PipelineResult now = bridge.run(kase, snaps);
        Map<String, Verdict> previous = snaps.size() >= 2
            ? byKey(bridge.run(kase, snaps.subList(0, snaps.size() - 1)))
            : Map.of();

        List<ProgressDto.Change> changes = new ArrayList<>();
        List<ProgressDto.Transition> transitions = new ArrayList<>();
        for (ItemVerdicts iv : now.verdicts()) {
            Item item = ObservationSet.STROKE.item(iv.code());
            for (Axis axis : AxisLabels.ORDER) {
                Optional<Verdict> ov = iv.axis(axis);
                if (ov.isEmpty()) {
                    continue;
                }
                Verdict v = ov.get();
                if (v.status() == Status.OBSERVED_ONCE || v.status() == Status.SUSTAINED) {
                    String from = Labels.of(axis, v.valueBefore());
                    String to = Labels.of(axis, v.currentValue());
                    changes.add(new ProgressDto.Change(item.code(), item.label(), axis.name(), AxisLabels.of(axis),
                        v.status().name(), v.duration(), from, to, message(v)));
                } else if (v.status() == Status.FLUCTUATING) {
                    Verdict prev = previous.get(iv.code() + "/" + axis);
                    if (prev != null && prev.status() == Status.SUSTAINED) {
                        transitions.add(new ProgressDto.Transition(item.code(), item.label(), axis.name(), transitionMessage(prev.duration())));
                    }
                }
            }
        }

        List<ProgressDto.Question> qs = questions.current(kase.getId())
            .map(QuestionCacheBody::questions).orElse(List.of()).stream()
            .map(q -> new ProgressDto.Question(q.rank(), q.type(), q.sentence(), q.source()))
            .toList();

        boolean silent = changes.isEmpty() && transitions.isEmpty() && qs.isEmpty();
        return new ProgressDto(week, silent, changes, transitions, qs);
    }

    public List<TrajectoryDto> trajectory(AuthContext ctx) {
        return trajectories.items(snapshots.findByCaseIdOrderByWeekAsc(ctx.kase().getId()));
    }

    private static Map<String, Verdict> byKey(PipelineResult r) {
        Map<String, Verdict> out = new HashMap<>();
        for (ItemVerdicts iv : r.verdicts()) {
            iv.byAxis().forEach((axis, v) -> out.put(iv.code() + "/" + axis, v));
        }
        return out;
    }

    /**
     * 설계 5절 문구 규칙. 조사는 엔진 Josa만 쓴다.
     *
     * 항목과 전이는 이 문장을 쓰는 화면이 이미 바로 위에 보여준다(설계 3절 상태 D
     * 예시: 제목 / "지켜보면 됨 → 혼자 하심" / "이 변화가 4주째 유지되고 있습니다").
     * 문장이 그 둘을 다시 풀어쓰면 같은 사실을 세 번 말하는 셈이고, 변화가 일곱 개면
     * 홈이 읽을 수 없는 글 벽이 된다. 그래서 문장은 위에 없는 것만 말한다.
     */
    static String message(Verdict v) {
        if (v.status() == Status.SUSTAINED) {
            return "이 변화가 " + v.duration() + "주째 유지되고 있습니다.";
        }
        return "한 번 달라진 것으로 관찰됐습니다. 아직 변화라고 보기 어렵습니다.";
    }

    static String transitionMessage(int previousDuration) {
        return previousDuration + "주 유지되던 변화가 이번 주에는 다르게 관찰됐습니다. 아직 어느 쪽인지 알기 어렵습니다.";
    }
}
