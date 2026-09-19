package nextvisit.api.common;

import java.time.LocalDate;
import nextvisit.api.cases.CaseEntity;
import org.springframework.stereotype.Component;

/** Resolves the calendar that belongs to one case without changing the server clock. */
@Component
public class CaseTimeline {

    private final WeekCalculator weeks;

    public CaseTimeline(WeekCalculator weeks) {
        this.weeks = weeks;
    }

    public LocalDate today(CaseEntity kase) {
        return kase.isDemoMode() ? kase.getDemoToday() : weeks.today();
    }

    public int currentWeek(CaseEntity kase) {
        return weeks.weekOf(kase.getStartDate(), today(kase));
    }
}
