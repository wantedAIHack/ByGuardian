package nextvisit.api.questions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import nextvisit.api.auth.AuthContext;
import nextvisit.api.cases.CaseEntity;
import nextvisit.api.cases.CaseRepository;
import nextvisit.api.common.Json;
import nextvisit.api.common.ValidationException;
import nextvisit.api.common.WeekCalculator;
import nextvisit.api.engine.EngineBridge;
import nextvisit.api.progress.AxisLabels;
import nextvisit.api.progress.TrajectoryMapper;
import nextvisit.api.snapshots.Snapshot;
import nextvisit.api.snapshots.SnapshotBody;
import nextvisit.api.snapshots.SnapshotRepository;
import nextvisit.engine.Axis;
import nextvisit.engine.Item;
import nextvisit.engine.ItemVerdicts;
import nextvisit.engine.Labels;
import nextvisit.engine.ObservationSet;
import nextvisit.engine.PipelineResult;
import nextvisit.engine.SignalAction;
import nextvisit.engine.SignalDetector;
import nextvisit.engine.SignalKind;
import nextvisit.engine.Status;
import nextvisit.engine.Verdict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PrepCardService {

    public static final String EMPTY_MESSAGE = "이번 기간 관찰에서 달라진 것이 없었습니다";
    public static final int GLANCE_MAX_LINES = 4;
    public static final int EXTRA_MAX = 5;
    public static final int EXTRA_MAX_LENGTH = 200;

    private final CaseRepository cases;
    private final SnapshotRepository snapshots;
    private final QuestionService questions;
    private final EngineBridge bridge;
    private final TrajectoryMapper trajectories;
    private final WeekCalculator weeks;
    private final Json json;

    public PrepCardService(CaseRepository cases, SnapshotRepository snapshots, QuestionService questions, EngineBridge bridge,
                           TrajectoryMapper trajectories, WeekCalculator weeks, Json json) {
        this.cases = cases;
        this.snapshots = snapshots;
        this.questions = questions;
        this.bridge = bridge;
        this.trajectories = trajectories;
        this.weeks = weeks;
        this.json = json;
    }

    public PrepCardDto card(AuthContext ctx) {
        CaseEntity kase = ctx.kase();
        List<Snapshot> snaps = snapshots.findByCaseIdOrderByWeekAsc(kase.getId());
        QuestionCacheBody cache = questions.current(kase.getId()).orElseGet(() -> questions.refresh(kase.getId()));
        PipelineResult r = bridge.run(kase, snaps);

        List<PrepCardDto.Question> qs = new ArrayList<>();
        for (QuestionCacheBody.Q q : cache.questions()) {
            List<PrepCardDto.EvidenceItem> items = new ArrayList<>();
            for (String code : q.items()) {
                Item item = ObservationSet.STROKE.item(code);
                for (Axis axis : axesFor(q.type())) {
                    var series = trajectories.series(snaps, code, axis);
                    if (!series.values().isEmpty()) {
                        items.add(new PrepCardDto.EvidenceItem(code, item.label(), axis.name(), AxisLabels.of(axis), series.values()));
                    }
                }
            }
            PrepCardDto.SignalEvidence signal = q.signal() == null ? null : signalEvidence(snaps, q.signal());
            qs.add(new PrepCardDto.Question(q.rank(), q.type(), q.sentence(), q.source(), new PrepCardDto.Evidence(items, signal)));
        }

        List<String> extra = Arrays.asList(json.fromJson(kase.getExtraQuestions(), String[].class));
        Set<String> preferredCodes = new HashSet<>();
        for (QuestionCacheBody.Q q : cache.questions()) {
            preferredCodes.addAll(q.items());
        }
        return new PrepCardDto(weeks.currentWeek(kase.getStartDate()), kase.getNextVisitDate(), qs, extra,
            qs.isEmpty() ? EMPTY_MESSAGE : null, glance(r, preferredCodes));
    }

    public List<String> saveExtra(AuthContext ctx, List<String> given) {
        if (given.size() > EXTRA_MAX) {
            throw new ValidationException("추가 질문은 " + EXTRA_MAX + "개까지입니다");
        }
        List<String> cleaned = new ArrayList<>();
        for (String q : given) {
            if (q == null || q.isBlank()) {
                throw new ValidationException("빈 질문은 넣을 수 없습니다");
            }
            if (q.length() > EXTRA_MAX_LENGTH) {
                throw new ValidationException("질문은 " + EXTRA_MAX_LENGTH + "자까지입니다");
            }
            cleaned.add(q.trim());
        }
        CaseEntity kase = cases.findById(ctx.kase().getId()).orElseThrow();
        kase.setExtraQuestions(json.toJson(cleaned));
        cases.save(kase);
        return cleaned;
    }

    /** 설계 5절: 감지 종류별 근거 축. */
    static List<Axis> axesFor(String type) {
        return switch (type) {
            case "AID_CHANGE" -> List.of(Axis.LEVEL, Axis.AID);
            case "HAND_DISUSE" -> List.of(Axis.LEVEL, Axis.HAND);
            case "CONSISTENCY_DROP" -> List.of(Axis.LEVEL, Axis.CONSISTENCY);
            case "TIME_OF_DAY" -> List.of();
            default -> List.of(Axis.LEVEL);
        };
    }

    /** 최근 기록 4주 창 안에서 그 동작·종류가 관찰된 주차. 스냅샷에서 직접 센다. */
    private PrepCardDto.SignalEvidence signalEvidence(List<Snapshot> snaps, QuestionCacheBody.SignalRef ref) {
        int from = Math.max(0, snaps.size() - SignalDetector.WINDOW_WEEKS);
        List<Snapshot> window = snaps.subList(from, snaps.size());
        List<Integer> hits = new ArrayList<>();
        for (Snapshot s : window) {
            SnapshotBody body = json.fromJson(s.getBody(), SnapshotBody.class);
            List<String> kinds = body.painSignal() == null ? null : body.painSignal().get(ref.action());
            if (kinds != null && kinds.contains(ref.kind())) {
                hits.add(s.getWeek());
            }
        }
        SignalAction action = SignalAction.valueOf(ref.action());
        SignalKind kind = SignalKind.valueOf(ref.kind());
        return new PrepCardDto.SignalEvidence(action.name(), action.phrase(), kind.name(), kindLabel(kind), hits, window.size());
    }

    /** 화면용 짧은 명사형. 엔진의 문장용 구("얼굴을 찡그리시는 걸")와 다르다. */
    public static String kindLabel(SignalKind kind) {
        return switch (kind) {
            case GRIMACE -> "찡그림";
            case VOCAL -> "소리 냄";
            case GUARDING -> "팔을 감싸거나 피함";
        };
    }

    /**
     * 설계 5절 therapistGlance. SUSTAINED 항목마다 한 줄, 값만.
     * 선택된 질문이 가리키는 항목(preferredCodes)을 카탈로그 순서로 먼저, 나머지를 카탈로그 순서로 이어 붙이고
     * 최대 GLANCE_MAX_LINES줄로 자른다 — 질문의 근거가 되는 소견이 잘려나가지 않도록.
     */
    static List<String> glance(PipelineResult r, Set<String> preferredCodes) {
        Map<String, String> byCode = new LinkedHashMap<>();
        for (ItemVerdicts iv : r.verdicts()) {
            Item item = ObservationSet.STROKE.item(iv.code());
            List<String> parts = new ArrayList<>();
            for (Axis axis : AxisLabels.ORDER) {
                Optional<Verdict> ov = iv.axis(axis);
                if (ov.isEmpty() || ov.get().status() != Status.SUSTAINED) {
                    continue;
                }
                Verdict v = ov.get();
                String prefix = axis == Axis.LEVEL ? "" : AxisLabels.of(axis) + ": ";
                parts.add(prefix + Labels.of(axis, v.valueBefore()) + " → " + Labels.of(axis, v.currentValue())
                    + " (" + v.since() + "주차부터)");
            }
            if (!parts.isEmpty()) {
                byCode.put(iv.code(), item.label() + ": " + String.join(", ", parts));
            }
        }
        List<String> lines = new ArrayList<>();
        for (var e : byCode.entrySet()) {
            if (preferredCodes.contains(e.getKey())) {
                lines.add(e.getValue());
            }
        }
        for (var e : byCode.entrySet()) {
            if (!preferredCodes.contains(e.getKey())) {
                lines.add(e.getValue());
            }
        }
        return lines.size() > GLANCE_MAX_LINES ? lines.subList(0, GLANCE_MAX_LINES) : lines;
    }
}
