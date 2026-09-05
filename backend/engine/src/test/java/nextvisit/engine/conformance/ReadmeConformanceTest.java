package nextvisit.engine.conformance;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import nextvisit.engine.Axis;
import nextvisit.engine.CaseInput;
import nextvisit.engine.Detection;
import nextvisit.engine.DetectionType;
import nextvisit.engine.Direction;
import nextvisit.engine.ItemVerdicts;
import nextvisit.engine.Labels;
import nextvisit.engine.Observation;
import nextvisit.engine.ObservationSet;
import nextvisit.engine.Pipeline;
import nextvisit.engine.PipelineResult;
import nextvisit.engine.SignalAction;
import nextvisit.engine.SignalDetector;
import nextvisit.engine.SignalKey;
import nextvisit.engine.SignalKind;
import nextvisit.engine.SignalPattern;
import nextvisit.engine.SignalWeek;
import nextvisit.engine.SilenceGate;
import nextvisit.engine.Source;
import nextvisit.engine.Status;
import nextvisit.engine.Templates;
import nextvisit.engine.TimeTag;
import nextvisit.engine.Verdict;
import nextvisit.engine.WeeklyNote;
import nextvisit.engine.demo.DemoSeed;
import org.junit.jupiter.api.Test;

/**
 * README 적합성 테스트. README가 "이렇게 동작해야 한다"고 쓴 문장을 검사 항목으로 옮긴 것.
 *
 * 단위 테스트와 일부 겹치지만 목적이 다르다. 여기는 별도 패키지에서 공개 API만 써서
 * 블랙박스로 검사하고, 각 항목이 README의 어느 절에서 왔는지 이름에 적는다.
 * 실패 메시지에 항목 ID와 실제 값이 나오므로 README와 코드 중 어느 쪽이 틀렸는지 바로 볼 수 있다.
 */
class ReadmeConformanceTest {

    // ---------- accumulator ----------

    private final List<String> failures = new ArrayList<>();
    private final List<String> sentences = new ArrayList<>();

    private void check(String id, boolean ok, String actual) {
        if (!ok) {
            failures.add(id + " — " + actual);
        }
    }

    private void done() {
        assertTrue(failures.isEmpty(), () -> String.join("\n", failures));
    }

    // ---------- builders ----------

    private static List<Observation> traj(String spec) {
        List<Observation> out = new ArrayList<>();
        String[] t = spec.trim().split("\\s+");
        for (int i = 0; i < t.length; i++) {
            if (t[i].equals("_")) {
                continue;
            }
            Source s = Source.CONFIRMED;
            String v = t[i];
            if (v.startsWith("c")) {
                s = Source.CARRIED;
                v = v.substring(1);
            }
            out.add(new Observation(i + 1, Integer.parseInt(v), s));
        }
        return out;
    }

    private static Verdict v(String spec) {
        return SilenceGate.judge(traj(spec));
    }

    private Map<String, Map<Axis, List<Observation>>> series = new LinkedHashMap<>();

    private void reset() {
        series = new LinkedHashMap<>();
    }

    private void put(String code, Axis axis, String spec) {
        series.computeIfAbsent(code, k -> new EnumMap<>(Axis.class)).put(axis, traj(spec));
    }

    private static SignalWeek sw(int week, SignalKey... keys) {
        return new SignalWeek(week, Set.of(keys));
    }

    private CaseInput kase(List<SignalWeek> signals, List<WeeklyNote> notes) {
        return new CaseInput(ObservationSet.STROKE, series, signals, notes);
    }

    private PipelineResult run(List<SignalWeek> signals, List<WeeklyNote> notes) {
        PipelineResult r = Pipeline.run(kase(signals, notes));
        sentences.addAll(r.sentences());
        return r;
    }

    private static Set<DetectionType> types(PipelineResult r) {
        return r.detections().stream().map(Detection::type).collect(Collectors.toSet());
    }

    private static String first(PipelineResult r, DetectionType t) {
        for (int i = 0; i < r.selected().size(); i++) {
            if (r.selected().get(i).type() == t) {
                return r.sentences().get(i);
            }
        }
        return null;
    }

    private static List<WeeklyNote> notes(TimeTag... tags) {
        List<WeeklyNote> out = new ArrayList<>();
        for (int i = 0; i < tags.length; i++) {
            out.add(new WeeklyNote(i + 1, tags[i]));
        }
        return out;
    }

    private static final SignalKey STAND_GRIMACE = new SignalKey(SignalAction.STANDING, SignalKind.GRIMACE);
    private static final SignalKey STAND_GUARD = new SignalKey(SignalAction.STANDING, SignalKind.GUARDING);
    private static final String Q2 = "일어설 때 얼굴을 찡그리시는 걸 4주 중 3주 봤습니다. 통증일 수 있을까요?";

    private static List<SignalWeek> grimace2356() {
        return List.of(sw(1), sw(2, STAND_GRIMACE), sw(3, STAND_GRIMACE), sw(4), sw(5, STAND_GRIMACE), sw(6, STAND_GRIMACE));
    }

    private static List<SignalWeek> noSignals6() {
        return List.of(sw(1), sw(2), sw(3), sw(4), sw(5), sw(6));
    }

    private static List<WeeklyNote> noNotes6() {
        return notes(null, null, null, null, null, null);
    }

    private static Map<Axis, Verdict> level(String spec) {
        Map<Axis, Verdict> m = new EnumMap<>(Axis.class);
        m.put(Axis.LEVEL, v(spec));
        return m;
    }

    // ---------- §1 검증된 것 ----------

    @Test
    void section1_verifiedQuestions() {
        PipelineResult seed = Pipeline.run(DemoSeed.stroke());
        check("A1 검증된 질문 2가 시드에서 문장 그대로",
            seed.sentences().size() > 1 && seed.sentences().get(1).equals(Q2),
            seed.sentences().size() > 1 ? seed.sentences().get(1) : "<none>");
        String q1 = seed.sentences().isEmpty() ? "" : seed.sentences().get(0);
        check("A2 검증된 질문 1이 같은 뼈대로",
            q1.contains("화장실") && q1.contains("걷기") && q1.contains("6주째 그대로입니다") && q1.endsWith("왜 안 늘고 있을까요?"),
            q1);
        ItemVerdicts grooming = seed.verdicts().stream().filter(x -> x.code().equals("grooming")).findFirst().orElseThrow();
        List<Integer> gv = grooming.level().trajectory().stream().map(Observation::value).toList();
        check("A3 흔들림 자체가 치료사 정보 — 궤적 그대로 반환",
            grooming.level().status() == Status.FLUCTUATING && gv.equals(List.of(2, 2, 3, 3, 2, 3)),
            grooming.level().status() + " " + gv);
        done();
    }

    // ---------- §4 3층 분리 ----------

    @Test
    void section4_threeLayers() {
        String[] rows = {"2 2 2 2 2 2", "2 2 3", "2 3 3", "1 1 1 2 2 2", "3 3 3 2 2 2", "1 1 2 2 3 3",
            "2 2 3 3 2", "2 2 3 3 2 3", "2 2 3 3 2 3 3 3 3", "2 2 _ 3 3"};
        boolean allTraj = true;
        for (String r : rows) {
            if (v(r).trajectory().size() != traj(r).size()) {
                allTraj = false;
            }
        }
        check("C1 trajectory는 판정과 무관하게 항상 반환", allTraj, "10 rows");

        PipelineResult seed = Pipeline.run(DemoSeed.stroke());
        boolean carriedOk = true;
        for (ItemVerdicts iv : seed.verdicts()) {
            for (Verdict vv : iv.byAxis().values()) {
                if (vv.trajectory().get(1).source() != Source.CARRIED || vv.trajectory().get(0).source() != Source.CONFIRMED) {
                    carriedOk = false;
                }
            }
        }
        check("C2 층 1은 값의 출처를 버리지 않음 (시드 2주차 carried)", carriedOk, seed.verdicts().size() + " items");
        done();
    }

    // ---------- §5 데이터 모델 ----------

    @Test
    void section5_dataModel() {
        check("D1 8항목 표 순서",
            ObservationSet.STROKE.codes().equals(List.of("transfer", "ambulation", "stairs", "toilet", "dressing", "grooming", "bathing", "feeding")),
            ObservationSet.STROKE.codes().toString());

        Map<String, Set<Axis>> ax = Map.of(
            "transfer", Set.of(Axis.LEVEL, Axis.AID, Axis.CONSISTENCY),
            "ambulation", Set.of(Axis.LEVEL, Axis.AID, Axis.CONSISTENCY),
            "stairs", Set.of(Axis.LEVEL, Axis.AID, Axis.CONSISTENCY),
            "toilet", Set.of(Axis.LEVEL, Axis.CONSISTENCY),
            "dressing", Set.of(Axis.LEVEL, Axis.CONSISTENCY, Axis.HAND),
            "grooming", Set.of(Axis.LEVEL, Axis.CONSISTENCY, Axis.HAND),
            "bathing", Set.of(Axis.LEVEL, Axis.CONSISTENCY),
            "feeding", Set.of(Axis.LEVEL, Axis.CONSISTENCY, Axis.HAND));
        boolean axOk = true;
        for (var e : ax.entrySet()) {
            if (!ObservationSet.STROKE.item(e.getKey()).axes().equals(e.getValue())) {
                axOk = false;
            }
        }
        check("D2 항목별 적용 축이 §5 표와 일치", axOk, "8 items");

        boolean lbl = Labels.of(Axis.LEVEL, 3).equals("혼자 하심") && Labels.of(Axis.LEVEL, 2).equals("지켜보면 됨")
            && Labels.of(Axis.LEVEL, 1).equals("손 잡아드림") && Labels.of(Axis.LEVEL, 0).equals("대부분 도움")
            && Labels.of(Axis.AID, 4).equals("아무것도 안 잡음") && Labels.of(Axis.AID, 3).equals("가구·난간 잡음")
            && Labels.of(Axis.AID, 2).equals("지팡이") && Labels.of(Axis.AID, 1).equals("워커") && Labels.of(Axis.AID, 0).equals("휠체어")
            && Labels.of(Axis.CONSISTENCY, 2).equals("매번") && Labels.of(Axis.CONSISTENCY, 1).equals("대체로") && Labels.of(Axis.CONSISTENCY, 0).equals("좋은 날만")
            && Labels.of(Axis.HAND, 2).equals("주로 씀") && Labels.of(Axis.HAND, 1).equals("거들기만") && Labels.of(Axis.HAND, 0).equals("안 씀");
        check("D3 표시 문구 14개가 §5 표와 일치", lbl, "level/aid/consistency/hand");

        reset();
        put("toilet", Axis.LEVEL, "2 2 2 2 2 2");
        put("toilet", Axis.HAND, "1 1 1 1 1 1");
        boolean rejHand;
        try {
            kase(noSignals6(), noNotes6());
            rejHand = false;
        } catch (IllegalArgumentException ex) {
            rejHand = ex.getMessage().contains("toilet") && ex.getMessage().contains("HAND");
        }
        check("D4 화장실에 마비 쪽 손 축 → 거부", rejHand, "IllegalArgumentException naming item+axis");

        reset();
        put("toilet", Axis.LEVEL, "2 2 4 2 2 2");
        boolean rejRange;
        try {
            kase(noSignals6(), noNotes6());
            rejRange = false;
        } catch (IllegalArgumentException ex) {
            rejRange = ex.getMessage().contains("toilet") && ex.getMessage().contains("3");
        }
        check("D5 도움 수준 4 → 거부 (최대 3)", rejRange, "IllegalArgumentException");

        reset();
        put("feeding", Axis.LEVEL, "2 2 2 2 2 2");
        boolean accSub;
        try {
            kase(noSignals6(), noNotes6());
            accSub = true;
        } catch (IllegalArgumentException ex) {
            accSub = false;
        }
        check("D6 조건부 축이 없는 항목 → 수용", accSub, "feeding LEVEL only");
        done();
    }

    // ---------- §6 침묵 게이트 ----------

    @Test
    void section6_silenceGate() {
        Object[][] table = {
            {"2 2 2 2 2 2", Status.NO_CHANGE, null, null, 6, 0},
            {"2 2 3", Status.OBSERVED_ONCE, Direction.UP, 3, 1, 0},
            {"2 3 3", Status.SUSTAINED, Direction.UP, 2, 2, 0},
            {"1 1 1 2 2 2", Status.SUSTAINED, Direction.UP, 4, 3, 0},
            {"3 3 3 2 2 2", Status.SUSTAINED, Direction.DOWN, 4, 3, 0},
            {"1 1 2 2 3 3", Status.SUSTAINED, Direction.UP, 3, 4, 0},
            {"2 2 3 3 2", Status.FLUCTUATING, Direction.DOWN, 5, 1, 1},
            {"2 2 3 3 2 3", Status.FLUCTUATING, Direction.UP, 6, 1, 2},
            {"2 2 3 3 2 3 3 3 3", Status.SUSTAINED, Direction.UP, 6, 4, 2},
            {"2 2 _ 3 3", Status.SUSTAINED, Direction.UP, 4, 2, 0},
        };
        for (Object[] row : table) {
            Verdict vv = v((String) row[0]);
            boolean ok = vv.status() == row[1] && vv.direction() == row[2] && Objects.equals(vv.since(), row[3])
                && vv.duration() == (int) row[4] && vv.reversals() == (int) row[5];
            check("E1 §6 예시 표 " + row[0], ok,
                vv.status() + "/" + vv.direction() + "/since " + vv.since() + "/dur " + vv.duration() + "/rev " + vv.reversals());
        }
        Verdict cv = v("2 2 3 c3 c3");
        check("E2 복사된 값도 관찰로 침", cv.status() == Status.SUSTAINED && cv.duration() == 3, cv.status() + " dur " + cv.duration());

        List<SignalPattern> p1 = SignalDetector.judge(List.of(sw(1), sw(2), sw(3), sw(4, STAND_GRIMACE)));
        List<SignalPattern> p2 = SignalDetector.judge(List.of(sw(1), sw(2, STAND_GRIMACE), sw(3), sw(4, STAND_GRIMACE)));
        check("E3 비언어 4주 중 1주 → 기록만, 2주 → 패턴", !p1.get(0).pattern() && p2.get(0).pattern(),
            "1/4=" + p1.get(0).pattern() + " 2/4=" + p2.get(0).pattern());
        List<SignalPattern> p3 = SignalDetector.judge(List.of(sw(1, STAND_GRIMACE), sw(2, STAND_GUARD), sw(3), sw(4)));
        check("E4 동작·신호 종류별로 따로 셈", p3.size() == 2 && !p3.get(0).pattern() && !p3.get(1).pattern(),
            "grimace 1/4, guarding 1/4");
        done();
    }

    // ---------- §7 교차 감지 ----------

    @Test
    void section7_crossDetection() {
        reset();
        put("toilet", Axis.LEVEL, "2 2 3 3 3 3");
        put("ambulation", Axis.LEVEL, "2 2 2 2 2 2");
        PipelineResult fa = run(noSignals6(), noNotes6());
        check("F1 (a) 그룹이 달라도 발화 (selfcare+mobility)", types(fa).contains(DetectionType.RISE_VS_STALL), String.valueOf(first(fa, DetectionType.RISE_VS_STALL)));

        reset();
        put("toilet", Axis.LEVEL, "2 2 2 2 2 3");
        put("ambulation", Axis.LEVEL, "2 2 2 2 2 2");
        check("F2 (a) 1주만 변화는 상승으로 안 침", !types(run(noSignals6(), noNotes6())).contains(DetectionType.RISE_VS_STALL), "observed_once");

        reset();
        put("transfer", Axis.LEVEL, "1 1 1 2 2 2");
        put("dressing", Axis.LEVEL, "3 3 3 2 2 2");
        PipelineResult fb = run(grimace2356(), noNotes6());
        String sb = first(fb, DetectionType.RISE_VS_DECLINE);
        check("F3 (b) 상승 대 하락", sb != null && sb.contains("도움이 덜 필요해지셨는데") && sb.contains("도움이 더 필요해지셨습니다"), String.valueOf(sb));

        reset();
        put("ambulation", Axis.LEVEL, "2 2 2 2 2 2");
        PipelineResult fc1 = run(grimace2356(), noNotes6());
        check("F4 (c) 정체+통증 → 검증된 질문 2", Q2.equals(first(fc1, DetectionType.STALL_WITH_PAIN)), String.valueOf(first(fc1, DetectionType.STALL_WITH_PAIN)));

        reset();
        put("toilet", Axis.LEVEL, "2 2 3 3 3 3");
        PipelineResult fc2 = run(grimace2356(), noNotes6());
        String sc2 = first(fc2, DetectionType.RISE_WITH_PAIN);
        check("F5 (c) 상승+통증, '좋아지' 금지", sc2 != null && sc2.contains("도움이 덜 필요해지셨는데") && sc2.contains("아파하시는 것도 봤습니다") && !sc2.contains("좋아지"), String.valueOf(sc2));

        reset();
        put("dressing", Axis.LEVEL, "3 3 3 2 2 2");
        PipelineResult fc3 = run(noSignals6(), noNotes6());
        String sc3 = first(fc3, DetectionType.DECLINE_NO_SIGNAL);
        check("F6 (c) 감소+신호 없음 → 확인 필요, '악화' 금지", sc3 != null && sc3.contains("도움이 더 필요해지셨습니다") && sc3.endsWith("어떻게 보시나요?") && !sc3.contains("악화"), String.valueOf(sc3));

        reset();
        put("dressing", Axis.LEVEL, "3 3 3 2 2 2");
        check("F7 (c) 감소+신호 있음 → 신호없음 규칙 침묵", !types(run(grimace2356(), noNotes6())).contains(DetectionType.DECLINE_NO_SIGNAL), "gated");

        reset();
        put("grooming", Axis.LEVEL, "2 2 3 3 2 3");
        PipelineResult fd = run(noSignals6(), noNotes6());
        String sd = first(fd, DetectionType.FLUCTUATION);
        check("F8 (d) 방향 전환 2회 → 발화", sd != null && sd.contains("반복하고 있습니다"), String.valueOf(sd));

        reset();
        put("grooming", Axis.LEVEL, "2 2 3 3 2");
        check("F8b (d) 방향 전환 1회 → 침묵",
            !types(run(List.of(sw(1), sw(2), sw(3), sw(4), sw(5)), notes(null, null, null, null, null))).contains(DetectionType.FLUCTUATION), "reversals=1");

        reset();
        put("toilet", Axis.LEVEL, "2 2 2 2 2 2");
        PipelineResult fe = run(noSignals6(), notes(null, null, TimeTag.AFTERNOON, TimeTag.AFTERNOON, null, TimeTag.AFTERNOON));
        check("F9 (e) 시간대 4주 중 3주", "오후에 대한 기록이 4주 중 3주 있습니다. 시간대와 관련이 있을까요?".equals(first(fe, DetectionType.TIME_OF_DAY)), String.valueOf(first(fe, DetectionType.TIME_OF_DAY)));

        reset();
        put("ambulation", Axis.LEVEL, "2 2 2 2 2 2");
        put("ambulation", Axis.AID, "1 1 1 1 2 2");
        PipelineResult ff = run(noSignals6(), noNotes6());
        String sf = first(ff, DetectionType.AID_CHANGE);
        check("F10 (f) 도움 수준 동일 + 도구 변화", sf != null && sf.contains("워커에서 지팡이로 바뀐 지 2주째"), String.valueOf(sf));

        reset();
        put("feeding", Axis.LEVEL, "2 2 2 3 3 3");
        put("feeding", Axis.HAND, "1 1 1 0 0 0");
        PipelineResult fg = run(noSignals6(), noNotes6());
        String sg = first(fg, DetectionType.HAND_DISUSE);
        check("F11 (g) 도움 수준 상승 + 마비 쪽 손 감소", sg != null && sg.contains("마비된 손은 안 씀으로"), String.valueOf(sg));

        reset();
        put("stairs", Axis.LEVEL, "1 1 1 1 1 1");
        put("stairs", Axis.CONSISTENCY, "2 2 1 0 0 0");
        PipelineResult fh = run(noSignals6(), noNotes6());
        String sh = first(fh, DetectionType.CONSISTENCY_DROP);
        check("F12 (h) 도움 수준 동일 + 일관성 하락", sh != null && sh.contains("요즘은 좋은 날만"), String.valueOf(sh));
        done();
    }

    @Test
    void section7_questionSelection() {
        reset();
        put("toilet", Axis.LEVEL, "2 2 3 3 3 3");
        put("transfer", Axis.LEVEL, "1 1 1 2 2 2");
        put("ambulation", Axis.LEVEL, "2 2 2 2 2 2");
        put("dressing", Axis.LEVEL, "3 3 3 2 2 2");
        PipelineResult frr = run(noSignals6(), noNotes6());
        List<DetectionType> rrTypes = frr.selected().stream().map(Detection::type).toList();
        check("F13 종류당 하나씩 라운드로빈 (단순 정렬이면 a,a,b)",
            rrTypes.equals(List.of(DetectionType.RISE_VS_STALL, DetectionType.RISE_VS_DECLINE, DetectionType.DECLINE_NO_SIGNAL)), rrTypes.toString());

        reset();
        put("grooming", Axis.LEVEL, "2 2 3 3 2 3");
        PipelineResult f1 = run(noSignals6(), noNotes6());
        check("F14 감지 1개 → 질문 1개, 빈 칸 없음", f1.selected().size() == 1 && f1.sentences().size() == 1, f1.sentences().size() + " sentence(s)");

        PipelineResult seed = Pipeline.run(DemoSeed.stroke());
        List<DetectionType> seedTypes = seed.selected().stream().map(Detection::type).toList();
        check("F15 시드 선택 순서 (a) → (c)정체+통증 → (g)",
            seedTypes.equals(List.of(DetectionType.RISE_VS_STALL, DetectionType.STALL_WITH_PAIN, DetectionType.HAND_DISUSE)), seedTypes.toString());
        check("F16 최대 3개", seed.selected().size() == 3 && seed.detections().size() > 3, seed.detections().size() + " → " + seed.selected().size());
        done();
    }

    // ---------- §9 침묵 상태 ----------

    @Test
    void section9_silentState() {
        reset();
        for (String c : ObservationSet.STROKE.codes()) {
            put(c, Axis.LEVEL, "2 2 2 2 2 2");
        }
        PipelineResult silent = run(noSignals6(), noNotes6());
        check("H1 변화 없음 → 0 감지, 0 질문", silent.detections().isEmpty() && silent.sentences().isEmpty(),
            silent.detections().size() + "/" + silent.sentences().size());
        done();
    }

    // ---------- §10 시드, 결정론 ----------

    @Test
    void section10_seedAndDeterminism() {
        PipelineResult seed = Pipeline.run(DemoSeed.stroke());
        Set<DetectionType> fired = types(seed);
        Set<DetectionType> expected = EnumSet.complementOf(EnumSet.of(DetectionType.DECLINE_NO_SIGNAL));
        check("I1 시드가 10종류 중 9종류 발화 (감소+신호없음 제외)", fired.equals(expected), fired.size() + " types");
        check("I2 시드 선택 항목",
            seed.selected().get(0).items().equals(List.of("toilet", "ambulation"))
                && seed.selected().get(1).items().equals(List.of("ambulation"))
                && seed.selected().get(1).signal().kind() == SignalKind.GRIMACE
                && seed.selected().get(2).items().equals(List.of("feeding")),
            "toilet×ambulation / ambulation+GRIMACE / feeding");

        PipelineResult again = Pipeline.run(DemoSeed.stroke());
        check("I4 같은 입력에 같은 출력", again.sentences().equals(seed.sentences()) && again.detections().equals(seed.detections()), "run twice");

        CaseInput s0 = DemoSeed.stroke();
        List<SignalWeek> revSig = new ArrayList<>(s0.signals());
        Collections.reverse(revSig);
        List<WeeklyNote> revNotes = new ArrayList<>(s0.notes());
        Collections.reverse(revNotes);
        PipelineResult shuffled = Pipeline.run(new CaseInput(s0.set(), s0.series(), revSig, revNotes));
        check("J1 신호·기록 순서를 뒤집어도 같은 질문", shuffled.sentences().equals(seed.sentences()), "reversed input");

        boolean stable = true;
        for (int i = 0; i < 50; i++) {
            if (!Pipeline.run(DemoSeed.stroke()).sentences().equals(seed.sentences())) {
                stable = false;
            }
        }
        check("J2 50회 반복 동일", stable, "50/50");
        done();
    }

    // ---------- §8 가드레일 ----------

    @Test
    void section8_guardrail() {
        List<String> readmeList = List.of("개선", "악화", "호전", "위험", "정상", "비정상", "회복",
            "좋아지", "좋아졌", "나빠지", "나빠졌", "나아지", "나아졌",
            "좋아져", "나빠져", "나아져", "좋아짐", "나빠짐", "나아짐");
        check("G1 FORBIDDEN이 README §8 목록과 같음", Templates.FORBIDDEN.equals(readmeList), Templates.FORBIDDEN.size() + " entries");
        check("G2a 평서문+금지어 거부", !Templates.isSafe("보행 기능이 개선되었습니다."), "");
        check("G2b 질문형이라도 활용형 거부 (졌/짐/져)",
            !Templates.isSafe("요즘 많이 좋아졌나요?") && !Templates.isSafe("좋아짐이 보일까요?") && !Templates.isSafe("나아져 보이는데 괜찮을까요?"), "");
        check("G2c 금지어 없어도 평서문 거부", !Templates.isSafe("집 안에서 걷는 걸 6주째 보고 있습니다."), "");
        check("G2d 깨끗한 질문 수용", Templates.isSafe("걷기는 왜 안 늘고 있을까요?"), "");
        String nfd = Normalizer.normalize("보행이 개선되었을까요?", Normalizer.Form.NFD);
        check("G2e NFD로 분해된 금지어 거부", !Templates.isSafe(nfd), "NFD");
        check("G2f §2 '쓰면 안 되는 것' 예시 거부 (병원 제외, API 계획)",
            !Templates.isSafe("보행 기능이 개선되었습니다") && !Templates.isSafe("낙상 위험이 감소했습니다")
                && !Templates.isSafe("회복이 또래보다 느립니다") && !Templates.isSafe("좋아지시는 것 같은데 괜찮을까요?"), "");
        done();
    }

    // ---------- §2 절대 규칙 — 템플릿 전수 조사 ----------

    @Test
    void section2_everyReachableSentenceIsSafe() {
        int rendered = 0;
        List<String> unsafe = new ArrayList<>();
        List<String> codes = ObservationSet.STROKE.codes();
        ObservationSet set = ObservationSet.STROKE;

        for (String x : codes) {
            for (String y : codes) {
                if (x.equals(y)) {
                    continue;
                }
                for (int lv = 1; lv <= 3; lv++) {
                    for (int w = 0; w <= 3; w++) {
                        Map<String, ItemVerdicts> bc = Map.of(
                            x, new ItemVerdicts(x, level((lv - 1) + " " + (lv - 1) + " " + lv + " " + lv + " " + lv + " " + lv)),
                            y, new ItemVerdicts(y, level(w + " " + w + " " + w + " " + w + " " + w + " " + w)));
                        String s = Templates.render(new Detection(DetectionType.RISE_VS_STALL, List.of(x, y), 4, null, null, 0, 0), set, bc);
                        rendered++;
                        if (!Templates.isSafe(s)) {
                            unsafe.add(s);
                        }
                        s = Templates.render(new Detection(DetectionType.RISE_VS_DECLINE, List.of(x, y), 4, null, null, 0, 0), set, bc);
                        rendered++;
                        if (!Templates.isSafe(s)) {
                            unsafe.add(s);
                        }
                    }
                }
            }
        }
        for (SignalAction a : SignalAction.values()) {
            for (SignalKind k : SignalKind.values()) {
                for (int win = 2; win <= 4; win++) {
                    for (int obs = 2; obs <= win; obs++) {
                        SignalKey key = new SignalKey(a, k);
                        for (String x : codes) {
                            String s = Templates.render(new Detection(DetectionType.STALL_WITH_PAIN, List.of(x), 6, key, null, obs, win), set,
                                Map.of(x, new ItemVerdicts(x, level("2 2 2 2 2 2"))));
                            rendered++;
                            if (!Templates.isSafe(s)) {
                                unsafe.add(s);
                            }
                            s = Templates.render(new Detection(DetectionType.RISE_WITH_PAIN, List.of(x), 4, key, null, obs, win), set,
                                Map.of(x, new ItemVerdicts(x, level("2 2 3 3 3 3"))));
                            rendered++;
                            if (!Templates.isSafe(s)) {
                                unsafe.add(s);
                            }
                        }
                    }
                }
            }
        }
        for (String x : codes) {
            for (int d = 1; d <= 8; d++) {
                Map<String, ItemVerdicts> bc = Map.of(x, new ItemVerdicts(x, level("3 3 3 2 2 2")));
                String s = Templates.render(new Detection(DetectionType.DECLINE_NO_SIGNAL, List.of(x), d, null, null, 0, 0), set, bc);
                rendered++;
                if (!Templates.isSafe(s)) {
                    unsafe.add(s);
                }
                s = Templates.render(new Detection(DetectionType.FLUCTUATION, List.of(x), d, null, null, 0, 0), set, bc);
                rendered++;
                if (!Templates.isSafe(s)) {
                    unsafe.add(s);
                }
            }
        }
        for (TimeTag t : List.of(TimeTag.MORNING, TimeTag.AFTERNOON, TimeTag.EVENING)) {
            for (int win = 3; win <= 4; win++) {
                for (int obs = 3; obs <= win; obs++) {
                    String s = Templates.render(new Detection(DetectionType.TIME_OF_DAY, List.of(), obs, null, t, obs, win), set, Map.of());
                    rendered++;
                    if (!Templates.isSafe(s)) {
                        unsafe.add(s);
                    }
                }
            }
        }
        for (String x : List.of("transfer", "ambulation", "stairs")) {
            for (int lv = 0; lv <= 3; lv++) {
                for (int b = 0; b <= 4; b++) {
                    for (int a = 0; a <= 4; a++) {
                        if (a == b) {
                            continue;
                        }
                        Map<Axis, Verdict> m = level(lv + " " + lv + " " + lv + " " + lv + " " + lv + " " + lv);
                        m.put(Axis.AID, v(b + " " + b + " " + b + " " + b + " " + a + " " + a));
                        String s = Templates.render(new Detection(DetectionType.AID_CHANGE, List.of(x), 2, null, null, 0, 0), set,
                            Map.of(x, new ItemVerdicts(x, m)));
                        rendered++;
                        if (!Templates.isSafe(s)) {
                            unsafe.add(s);
                        }
                    }
                }
            }
        }
        for (String x : List.of("dressing", "grooming", "feeding")) {
            for (int lv = 1; lv <= 3; lv++) {
                for (int h = 0; h <= 1; h++) {
                    Map<Axis, Verdict> m = level((lv - 1) + " " + (lv - 1) + " " + (lv - 1) + " " + lv + " " + lv + " " + lv);
                    m.put(Axis.HAND, v((h + 1) + " " + (h + 1) + " " + (h + 1) + " " + h + " " + h + " " + h));
                    String s = Templates.render(new Detection(DetectionType.HAND_DISUSE, List.of(x), 3, null, null, 0, 0), set,
                        Map.of(x, new ItemVerdicts(x, m)));
                    rendered++;
                    if (!Templates.isSafe(s)) {
                        unsafe.add(s);
                    }
                }
            }
        }
        for (String x : codes) {
            for (int lv = 0; lv <= 3; lv++) {
                for (int c = 0; c <= 1; c++) {
                    Map<Axis, Verdict> m = level(lv + " " + lv + " " + lv + " " + lv + " " + lv + " " + lv);
                    m.put(Axis.CONSISTENCY, v("2 2 " + (c + 1) + " " + c + " " + c + " " + c));
                    String s = Templates.render(new Detection(DetectionType.CONSISTENCY_DROP, List.of(x), 4, null, null, 0, 0), set,
                        Map.of(x, new ItemVerdicts(x, m)));
                    rendered++;
                    if (!Templates.isSafe(s)) {
                        unsafe.add(s);
                    }
                }
            }
        }
        check("B4 도달 가능한 템플릿 출력 전부 isSafe", unsafe.isEmpty(),
            rendered + " rendered, " + unsafe.size() + " unsafe" + (unsafe.isEmpty() ? "" : " e.g. " + unsafe.get(0)));
        check("B4 3000개 이상 조합을 실제로 돌렸는지", rendered > 3000, rendered + " rendered");
        done();
    }

    @Test
    void section2_noForbiddenPhraseInAnyGeneratedSentence() {
        // §7의 모든 규칙을 한 번씩 발화시켜 문장을 모은다
        section7_crossDetection();
        section7_questionSelection();
        sentences.addAll(Pipeline.run(DemoSeed.stroke()).sentences());

        List<String> banned = List.of("개선", "위험", "회복", "병원에 가보셔야", "좋아지", "악화", "호전", "정상");
        List<String> hits = new ArrayList<>();
        for (String s : sentences) {
            for (String b : banned) {
                if (s.contains(b)) {
                    hits.add(b + " in: " + s);
                }
            }
        }
        check("B1 생성된 어떤 문장에도 §2 금지 표현 없음", hits.isEmpty(), sentences.size() + " sentences" + (hits.isEmpty() ? "" : " — " + hits.get(0)));
        check("B3 모든 문장이 물음표로 끝남", sentences.stream().allMatch(s -> s.endsWith("?")), sentences.size() + " sentences");
        check("B0 문장이 실제로 모였는지", sentences.size() >= 15, String.valueOf(sentences.size()));
        done();
    }
}
