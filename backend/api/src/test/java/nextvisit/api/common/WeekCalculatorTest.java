package nextvisit.api.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class WeekCalculatorTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final LocalDate START = LocalDate.of(2026, 9, 5);

    private static WeekCalculator at(LocalDate date) {
        return new WeekCalculator(Clock.fixed(ZonedDateTime.of(date.atTime(10, 0), SEOUL).toInstant(), SEOUL));
    }

    @Test
    void weekBoundaries() {
        assertEquals(1, at(START).weekOf(START, START));
        assertEquals(1, at(START).weekOf(START, START.plusDays(6)));
        assertEquals(2, at(START).weekOf(START, START.plusDays(7)));
        assertEquals(4, at(START).weekOf(START, START.plusDays(27)));
        assertEquals(5, at(START).weekOf(START, START.plusDays(28)));
        assertEquals(6, at(START).weekOf(START, START.plusDays(35)));
    }

    @Test
    void currentWeekUsesClockInSeoul() {
        assertEquals(1, at(START).currentWeek(START));
        assertEquals(2, at(START.plusDays(7)).currentWeek(START));
        assertEquals(START.plusDays(7), at(START.plusDays(7)).today());
    }

    @Test
    void dateBeforeStartIsWeekOne() {
        assertEquals(1, at(START).weekOf(START, START.minusDays(3)));
    }

    @Test
    void fullRecheckEveryFourthWeek() {
        WeekCalculator c = at(START);
        assertFalse(c.isFullRecheck(1));
        assertFalse(c.isFullRecheck(3));
        assertTrue(c.isFullRecheck(4));
        assertFalse(c.isFullRecheck(5));
        assertTrue(c.isFullRecheck(8));
    }
}
