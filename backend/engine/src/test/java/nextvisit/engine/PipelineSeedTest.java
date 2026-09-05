package nextvisit.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import nextvisit.engine.demo.DemoSeed;
import org.junit.jupiter.api.Test;

/** README §10 시드가 §1의 검증된 두 질문과 §7 (a)~(h) 전부를 발화시키는지. */
class PipelineSeedTest {

    final PipelineResult r = Pipeline.run(DemoSeed.stroke());

    ItemVerdicts v(String code) {
        return r.verdicts().stream().filter(x -> x.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void levelVerdictsMatchSeedComments() {
        assertTrue(v("toilet").level().is(Status.SUSTAINED, Direction.UP));
        assertEquals(Status.NO_CHANGE, v("ambulation").level().status());
        assertEquals(Status.FLUCTUATING, v("grooming").level().status());
        assertEquals(2, v("grooming").level().reversals());
        assertTrue(v("transfer").level().is(Status.SUSTAINED, Direction.UP));
        assertTrue(v("feeding").level().is(Status.SUSTAINED, Direction.UP));
        assertTrue(v("dressing").level().is(Status.SUSTAINED, Direction.DOWN));
        assertEquals(Status.NO_CHANGE, v("stairs").level().status());
        assertEquals(Status.NO_CHANGE, v("bathing").level().status());
    }

    @Test
    void axisVerdictsMatchSeedComments() {
        assertTrue(v("ambulation").axis(Axis.AID).orElseThrow().is(Status.SUSTAINED, Direction.UP));
        assertTrue(v("feeding").axis(Axis.HAND).orElseThrow().is(Status.SUSTAINED, Direction.DOWN));
        assertTrue(v("stairs").axis(Axis.CONSISTENCY).orElseThrow().is(Status.SUSTAINED, Direction.DOWN));
    }

    @Test
    void week2IsCarriedForEveryItem() {
        for (ItemVerdicts iv : r.verdicts()) {
            for (Verdict verdict : iv.byAxis().values()) {
                assertEquals(Source.CARRIED, verdict.trajectory().get(1).source(), iv.code());
                assertEquals(Source.CONFIRMED, verdict.trajectory().get(0).source(), iv.code());
            }
        }
    }

    @Test
    void grimaceAndGuardingArePatterns() {
        assertEquals(2, r.patterns().size());
        assertTrue(r.patterns().stream().allMatch(SignalPattern::pattern));
        assertEquals(3, r.patterns().get(0).weeksObserved());
        assertEquals(4, r.patterns().get(0).windowWeeks());
    }

    @Test
    void everyDetectionTypeFiresExceptNoSignalRule() {
        // 시드에는 통증 패턴이 있으므로 (c) "참여 감소 + 신호 없음"만 발화하지 않는다
        Set<DetectionType> fired = r.detections().stream().map(Detection::type).collect(Collectors.toSet());
        assertEquals(EnumSet.complementOf(EnumSet.of(DetectionType.DECLINE_NO_SIGNAL)), fired);
    }

    @Test
    void selectsTheThreeSpecSaysItWill() {
        assertEquals(3, r.selected().size());
        assertEquals(DetectionType.RISE_VS_STALL, r.selected().get(0).type());
        assertEquals(List.of("toilet", "ambulation"), r.selected().get(0).items());
        assertEquals(DetectionType.STALL_WITH_PAIN, r.selected().get(1).type());
        assertEquals(List.of("ambulation"), r.selected().get(1).items());
        assertEquals(SignalKind.GRIMACE, r.selected().get(1).signal().kind());
        assertEquals(DetectionType.HAND_DISUSE, r.selected().get(2).type());
        assertEquals(List.of("feeding"), r.selected().get(2).items());
    }

    @Test
    void verifiedQuestionsComeOutOfTheSeed() {
        assertTrue(r.sentences().get(0).contains("6주째 그대로입니다"), r.sentences().get(0));
        assertTrue(r.sentences().get(0).endsWith("왜 안 늘고 있을까요?"), r.sentences().get(0));
        assertEquals("일어설 때 얼굴을 찡그리시는 걸 4주 중 3주 봤습니다. 통증일 수 있을까요?", r.sentences().get(1));
    }

    @Test
    void everySentenceIsSafe() {
        for (String s : r.sentences()) {
            assertTrue(Templates.isSafe(s), s);
        }
    }

    @Test
    void isDeterministic() {
        PipelineResult again = Pipeline.run(DemoSeed.stroke());
        assertEquals(r.sentences(), again.sentences());
        assertEquals(r.detections(), again.detections());
    }
}
