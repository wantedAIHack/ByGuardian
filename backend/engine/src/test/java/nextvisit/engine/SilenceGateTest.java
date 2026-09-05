package nextvisit.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SilenceGateTest {

    /** README §6 예시 표 그대로. 열: 궤적, status, direction, since, duration, reversals */
    @ParameterizedTest(name = "[{index}] {0} → {1}")
    @CsvSource(delimiter = '|', textBlock = """
        2 2 2 2 2 2         | NO_CHANGE     |      |   | 6 | 0
        2 2 3               | OBSERVED_ONCE | UP   | 3 | 1 | 0
        2 3 3               | SUSTAINED     | UP   | 2 | 2 | 0
        1 1 1 2 2 2         | SUSTAINED     | UP   | 4 | 3 | 0
        3 3 3 2 2 2         | SUSTAINED     | DOWN | 4 | 3 | 0
        1 1 2 2 3 3         | SUSTAINED     | UP   | 3 | 4 | 0
        2 2 3 3 2           | FLUCTUATING   | DOWN | 5 | 1 | 1
        2 2 3 3 2 3         | FLUCTUATING   | UP   | 6 | 1 | 2
        2 2 3 3 2 3 3 3 3   | SUSTAINED     | UP   | 6 | 4 | 2
        2 2 _ 3 3           | SUSTAINED     | UP   | 4 | 2 | 0
        """)
    void judgesSpecTable(String spec, Status status, String direction, String since, int duration, int reversals) {
        Verdict v = SilenceGate.judge(TestTrajectories.parse(spec));

        assertEquals(status, v.status());
        if (direction == null) {
            assertNull(v.direction());
        } else {
            assertEquals(Direction.valueOf(direction), v.direction());
        }
        if (since == null) {
            assertNull(v.since());
        } else {
            assertEquals(Integer.valueOf(since), v.since());
        }
        assertEquals(duration, v.duration());
        assertEquals(reversals, v.reversals());
    }

    @Test
    void alwaysReturnsWholeTrajectory() {
        List<Observation> input = TestTrajectories.parse("2 2 3 3 2 3");
        Verdict v = SilenceGate.judge(input);
        assertEquals(input, v.trajectory());
    }

    @Test
    void carriedValuesCountAsObservations() {
        // 3주차에 확인, 4·5주차는 "없음" 탭으로 복사 → 유지 3주 → sustained
        Verdict v = SilenceGate.judge(TestTrajectories.parse("2 2 3 c3 c3"));
        assertEquals(Status.SUSTAINED, v.status());
        assertEquals(3, v.duration());
    }

    @Test
    void singleWeekIsNoChange() {
        Verdict v = SilenceGate.judge(TestTrajectories.parse("2"));
        assertEquals(Status.NO_CHANGE, v.status());
        assertEquals(1, v.duration());
    }

    @Test
    void rejectsEmpty() {
        assertThrows(IllegalArgumentException.class, () -> SilenceGate.judge(List.of()));
    }

    @Test
    void rejectsNonIncreasingWeeks() {
        List<Observation> bad = List.of(
            new Observation(2, 1, Source.CONFIRMED),
            new Observation(2, 1, Source.CONFIRMED));
        assertThrows(IllegalArgumentException.class, () -> SilenceGate.judge(bad));
    }
}
