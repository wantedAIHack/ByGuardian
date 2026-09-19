package nextvisit.api.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import nextvisit.api.cases.CaseEntity;
import nextvisit.api.cases.Diagnosis;
import nextvisit.api.cases.PareticSide;
import nextvisit.api.cases.VerbalDifficulty;
import org.junit.jupiter.api.Test;

class CaseTimelineTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant CREATED = Instant.parse("2026-09-19T01:00:00Z");
    private static final LocalDate REAL_TODAY = LocalDate.of(2026, 9, 19);

    private final Clock clock = Clock.fixed(CREATED, SEOUL);
    private final CaseTimeline timeline = new CaseTimeline(new WeekCalculator(clock));

    @Test
    void ordinaryCaseUsesTheRealClockAndCannotAdvance() {
        CaseEntity kase = ordinary(REAL_TODAY.minusDays(7));

        assertFalse(kase.isDemoMode());
        assertEquals(REAL_TODAY, timeline.today(kase));
        assertEquals(2, timeline.currentWeek(kase));
        assertThrows(IllegalStateException.class, kase::advanceDemoWeek);
    }

    @Test
    void demoCaseUsesItsStoredDateAndAdvancesExactlySevenDays() {
        CaseEntity kase = CaseEntity.demo("stroke", REAL_TODAY, Diagnosis.STROKE, PareticSide.RIGHT,
            VerbalDifficulty.OFTEN, REAL_TODAY.plusWeeks(3), "hash", CREATED, REAL_TODAY);

        assertTrue(kase.isDemoMode());
        assertEquals(REAL_TODAY, timeline.today(kase));
        assertEquals(1, timeline.currentWeek(kase));

        kase.advanceDemoWeek();

        assertEquals(REAL_TODAY.plusDays(7), kase.getDemoToday());
        assertEquals(2, timeline.currentWeek(kase));
    }

    private CaseEntity ordinary(LocalDate start) {
        return new CaseEntity("stroke", start, Diagnosis.STROKE, PareticSide.RIGHT, VerbalDifficulty.OFTEN,
            REAL_TODAY.plusWeeks(3), "hash", CREATED);
    }
}
