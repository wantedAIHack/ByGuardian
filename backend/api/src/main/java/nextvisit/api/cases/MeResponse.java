package nextvisit.api.cases;

import java.time.LocalDate;
import java.util.UUID;

public record MeResponse(
    UUID caseId,
    String relation,
    LocalDate today,
    int week,
    boolean fullRecheck,
    boolean signalsEnabled,
    boolean handEnabled,
    boolean canRecordThisWeek,
    boolean recordedThisWeek,
    Integer lastRecordedWeek,
    LocalDate nextVisitDate
) {}
