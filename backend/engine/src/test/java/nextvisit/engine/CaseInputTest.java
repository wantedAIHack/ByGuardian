package nextvisit.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** CaseInput 입력 경계 검증. README §5: 항목이 선언하지 않은 축, 축 범위를 벗어난 값은 거부한다. */
class CaseInputTest {

    private static Observation obs(int week, int value) {
        return new Observation(week, value, Source.CONFIRMED);
    }

    @Test
    void validInputIsAccepted() {
        Map<String, Map<Axis, List<Observation>>> series = Map.of(
            "toilet", Map.of(Axis.LEVEL, List.of(obs(1, 2), obs(2, 2))));
        assertDoesNotThrow(() -> new CaseInput(ObservationSet.STROKE, series, List.of(), List.of()));
    }

    @Test
    void undeclaredAxisForItemThrows() {
        // toilet은 LEVEL, CONSISTENCY만 선언한다. HAND는 없다
        Map<String, Map<Axis, List<Observation>>> series = Map.of(
            "toilet", Map.of(
                Axis.LEVEL, List.of(obs(1, 2)),
                Axis.HAND, List.of(obs(1, 1))));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> new CaseInput(ObservationSet.STROKE, series, List.of(), List.of()));
        assertTrue(ex.getMessage().contains("toilet"), ex.getMessage());
        assertTrue(ex.getMessage().contains("HAND"), ex.getMessage());
    }

    @Test
    void outOfRangeValueThrowsWithItemCodeAndWeek() {
        // LEVEL은 0..3. 4는 범위 밖
        Map<String, Map<Axis, List<Observation>>> series = Map.of(
            "toilet", Map.of(Axis.LEVEL, List.of(obs(1, 2), obs(2, 4))));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> new CaseInput(ObservationSet.STROKE, series, List.of(), List.of()));
        assertTrue(ex.getMessage().contains("toilet"), ex.getMessage());
        assertTrue(ex.getMessage().contains("2"), ex.getMessage());
        assertTrue(ex.getMessage().contains("4"), ex.getMessage());
    }
}
