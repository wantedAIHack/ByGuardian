package nextvisit.api.cases;

import java.time.LocalDate;

/** PATCH /me. null이면 다음 방문일을 지운다. */
public record UpdateCaseRequest(LocalDate nextVisitDate) {}
