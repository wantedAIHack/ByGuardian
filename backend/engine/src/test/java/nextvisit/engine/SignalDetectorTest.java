package nextvisit.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SignalDetectorTest {

    private static final SignalKey STAND_GRIMACE = new SignalKey(SignalAction.STANDING, SignalKind.GRIMACE);
    private static final SignalKey STAND_GUARD = new SignalKey(SignalAction.STANDING, SignalKind.GUARDING);

    private static SignalWeek week(int w, SignalKey... keys) {
        return new SignalWeek(w, Set.of(keys));
    }

    @Test
    void seedGrimaceIsThreeOfFour() {
        // README §10: · ● ● · ● ●  → 최근 4주(3~6) 중 3주
        List<SignalPattern> out = SignalDetector.judge(List.of(
            week(1), week(2, STAND_GRIMACE), week(3, STAND_GRIMACE),
            week(4), week(5, STAND_GRIMACE), week(6, STAND_GRIMACE)));

        assertEquals(1, out.size());
        SignalPattern p = out.get(0);
        assertEquals(STAND_GRIMACE, p.key());
        assertEquals(3, p.weeksObserved());
        assertEquals(4, p.windowWeeks());
        assertTrue(p.pattern());
    }

    @Test
    void oneWeekInWindowIsRecordedOnly() {
        List<SignalPattern> out = SignalDetector.judge(List.of(
            week(1, STAND_GRIMACE), week(2), week(3), week(4), week(5, STAND_GRIMACE)));
        assertEquals(1, out.get(0).weeksObserved());
        assertFalse(out.get(0).pattern());
    }

    @Test
    void windowIsLastFourRecordedWeeksNotCalendar() {
        // 주차 1,2,3,7 기록. 창은 기록된 4주 전부
        List<SignalPattern> out = SignalDetector.judge(List.of(
            week(1, STAND_GRIMACE), week(2, STAND_GRIMACE), week(3), week(7)));
        assertEquals(2, out.get(0).weeksObserved());
        assertEquals(4, out.get(0).windowWeeks());
        assertTrue(out.get(0).pattern());
    }

    @Test
    void shortHistoryUsesSmallerWindow() {
        List<SignalPattern> out = SignalDetector.judge(List.of(week(1, STAND_GRIMACE), week(2, STAND_GRIMACE)));
        assertEquals(2, out.get(0).windowWeeks());
        assertTrue(out.get(0).pattern());
    }

    @Test
    void countsEachKeySeparatelyAndSortsByActionThenKind() {
        List<SignalPattern> out = SignalDetector.judge(List.of(
            week(1, STAND_GUARD), week(2, STAND_GRIMACE, STAND_GUARD), week(3, STAND_GRIMACE), week(4)));
        assertEquals(2, out.size());
        assertEquals(STAND_GRIMACE, out.get(0).key());
        assertEquals(STAND_GUARD, out.get(1).key());
    }

    @Test
    void noSignalsGivesEmptyList() {
        assertTrue(SignalDetector.judge(List.of(week(1), week(2))).isEmpty());
        assertTrue(SignalDetector.judge(List.of()).isEmpty());
    }
}
