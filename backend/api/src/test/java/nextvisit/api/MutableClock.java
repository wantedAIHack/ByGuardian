package nextvisit.api;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

public class MutableClock extends Clock {
    private final ZoneId zone;
    private Instant instant;

    public MutableClock(ZoneId zone, LocalDate date) {
        this.zone = zone;
        set(date);
    }

    public void set(LocalDate date) {
        this.instant = date.atTime(10, 0).atZone(zone).toInstant();
    }

    public void advanceDays(int days) {
        this.instant = instant.plusSeconds(86_400L * days);
    }

    @Override public ZoneId getZone() { return zone; }
    @Override public Clock withZone(ZoneId z) { return new MutableClock(z, LocalDate.ofInstant(instant, z)); }
    @Override public Instant instant() { return instant; }
}
