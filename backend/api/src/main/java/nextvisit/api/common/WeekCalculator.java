package nextvisit.api.common;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** README §3(설계) 주차 규칙. week = floor(days/7) + 1. 4의 배수 주는 전체 재확인. */
@Component
public class WeekCalculator {
    public static final int FULL_RECHECK_EVERY = 4;

    private final Clock clock;

    public WeekCalculator(Clock clock) {
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public int weekOf(LocalDate start, LocalDate date) {
        long days = ChronoUnit.DAYS.between(start, date);
        if (days < 0) {
            return 1;
        }
        return (int) (days / 7) + 1;
    }

    public int currentWeek(LocalDate start) {
        return weekOf(start, today());
    }

    public boolean isFullRecheck(int week) {
        return week % FULL_RECHECK_EVERY == 0;
    }
}
