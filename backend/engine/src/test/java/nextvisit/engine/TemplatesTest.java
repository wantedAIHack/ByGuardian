package nextvisit.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class TemplatesTest {

    static final ObservationSet SET = ObservationSet.STROKE;
    static final SignalPattern GRIMACE_3_OF_4 =
        new SignalPattern(new SignalKey(SignalAction.STANDING, SignalKind.GRIMACE), 3, 4, true);

    static ItemVerdicts item(String code, String level) {
        Map<Axis, Verdict> m = new EnumMap<>(Axis.class);
        m.put(Axis.LEVEL, SilenceGate.judge(TestTrajectories.parse(level)));
        return new ItemVerdicts(code, m);
    }

    static ItemVerdicts item(String code, String level, Axis extra, String spec) {
        Map<Axis, Verdict> m = new EnumMap<>(Axis.class);
        m.put(Axis.LEVEL, SilenceGate.judge(TestTrajectories.parse(level)));
        m.put(extra, SilenceGate.judge(TestTrajectories.parse(spec)));
        return new ItemVerdicts(code, m);
    }

    static Map<String, ItemVerdicts> byCode(ItemVerdicts... vs) {
        return List.of(vs).stream().collect(Collectors.toMap(ItemVerdicts::code, Function.identity()));
    }

    @Test
    void verifiedQuestionTwoIsVerbatim() {
        Detection d = Detection.ofSignal(DetectionType.STALL_WITH_PAIN, "ambulation", 6, GRIMACE_3_OF_4);
        String s = Templates.render(d, SET, byCode(item("ambulation", "2 2 2 2 2 2")));
        assertEquals("일어설 때 얼굴을 찡그리시는 걸 4주 중 3주 봤습니다. 통증일 수 있을까요?", s);
    }

    @Test
    void riseVsStallMentionsBothItemsAndWeeks() {
        Detection d = Detection.ofItems(DetectionType.RISE_VS_STALL, List.of("toilet", "ambulation"), 4);
        String s = Templates.render(d, SET, byCode(item("toilet", "2 2 3 3 3 3"), item("ambulation", "2 2 2 2 2 2")));
        assertEquals("화장실 이용은 혼자 하심으로 바뀌셨는데 집 안에서 걷기는 6주째 그대로입니다. 집 안에서 걷기는 왜 안 늘고 있을까요?", s);
    }

    @Test
    void aidChangeShowsBeforeAndAfter() {
        Detection d = Detection.ofItems(DetectionType.AID_CHANGE, List.of("ambulation"), 2);
        String s = Templates.render(d, SET, byCode(item("ambulation", "2 2 2 2 2 2", Axis.AID, "1 1 1 1 2 2")));
        assertEquals("집 안에서 걷기는 지켜보면 됨 그대로인데 워커에서 지팡이로 바뀐 지 2주째입니다. 이대로 괜찮을까요?", s);
    }

    @Test
    void handDisuse() {
        Detection d = Detection.ofItems(DetectionType.HAND_DISUSE, List.of("feeding"), 3);
        String s = Templates.render(d, SET, byCode(item("feeding", "2 2 2 3 3 3", Axis.HAND, "1 1 1 0 0 0")));
        assertEquals("식사는 혼자 하심으로 바뀌셨는데 마비된 손은 안 씀으로 바뀌었습니다. 괜찮은 걸까요?", s);
    }

    @Test
    void consistencyDrop() {
        Detection d = Detection.ofItems(DetectionType.CONSISTENCY_DROP, List.of("stairs"), 4);
        String s = Templates.render(d, SET, byCode(item("stairs", "1 1 1 1 1 1", Axis.CONSISTENCY, "2 2 1 0 0 0")));
        assertEquals("문턱·계단은 손 잡아드림 그대로인데 요즘은 좋은 날만입니다. 어떻게 보시나요?", s);
    }

    @Test
    void timeOfDay() {
        Detection d = Detection.ofTime(TimeTag.AFTERNOON, 3, 4);
        assertEquals("오후에 대한 기록이 4주 중 3주 있습니다. 시간대와 관련이 있을까요?", Templates.render(d, SET, Map.of()));
    }

    @Test
    void everyTypeRendersSafeQuestion() {
        Map<String, ItemVerdicts> v = byCode(
            item("toilet", "2 2 3 3 3 3"),
            item("ambulation", "2 2 2 2 2 2", Axis.AID, "1 1 1 1 2 2"),
            item("dressing", "3 3 3 2 2 2"),
            item("grooming", "2 2 3 3 2 3"),
            item("feeding", "2 2 2 3 3 3", Axis.HAND, "1 1 1 0 0 0"),
            item("stairs", "1 1 1 1 1 1", Axis.CONSISTENCY, "2 2 1 0 0 0"));
        List<Detection> all = List.of(
            Detection.ofItems(DetectionType.RISE_VS_STALL, List.of("toilet", "ambulation"), 4),
            Detection.ofItems(DetectionType.RISE_VS_DECLINE, List.of("toilet", "dressing"), 4),
            Detection.ofSignal(DetectionType.STALL_WITH_PAIN, "ambulation", 6, GRIMACE_3_OF_4),
            Detection.ofSignal(DetectionType.RISE_WITH_PAIN, "toilet", 4, GRIMACE_3_OF_4),
            Detection.ofItems(DetectionType.DECLINE_NO_SIGNAL, List.of("dressing"), 3),
            Detection.ofItems(DetectionType.FLUCTUATION, List.of("grooming"), 6),
            Detection.ofTime(TimeTag.AFTERNOON, 3, 4),
            Detection.ofItems(DetectionType.AID_CHANGE, List.of("ambulation"), 2),
            Detection.ofItems(DetectionType.HAND_DISUSE, List.of("feeding"), 3),
            Detection.ofItems(DetectionType.CONSISTENCY_DROP, List.of("stairs"), 4));

        assertEquals(DetectionType.values().length, all.size());
        for (Detection d : all) {
            String s = Templates.render(d, SET, v);
            assertTrue(Templates.isSafe(s), d.type() + " unsafe: " + s);
        }
    }

    @Test
    void isSafeRejectsForbiddenWordsAndStatements() {
        assertFalse(Templates.isSafe("보행 기능이 개선되었습니다."));
        assertFalse(Templates.isSafe("좋아지고 있는 걸까요?"));
        assertFalse(Templates.isSafe("낙상 위험이 줄었을까요?"));
        assertTrue(Templates.isSafe("걷기는 왜 안 늘고 있을까요?"));
    }
}
