package nextvisit.api.demo;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import nextvisit.api.auth.Guardian;
import nextvisit.api.auth.GuardianRepository;
import nextvisit.api.auth.TokenService;
import nextvisit.api.cases.CaseEntity;
import nextvisit.api.cases.CaseRepository;
import nextvisit.api.cases.CaseService;
import nextvisit.api.cases.Diagnosis;
import nextvisit.api.cases.PareticSide;
import nextvisit.api.cases.VerbalDifficulty;
import nextvisit.api.common.WeekCalculator;
import nextvisit.api.questions.QuestionService;
import nextvisit.api.therapist.TherapistSummaryService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Deterministic six-week browser fixture. The bean is absent unless an isolated
 * test environment explicitly enables it, so the production API has no seed route.
 */
@RestController
@ConditionalOnProperty(name = "nextvisit.internal-seed.enabled", havingValue = "true")
class DemoSeedTestController {

    record Response(UUID caseId, String guardianToken, String recoveryCode, String therapistUrl,
                    String therapistToken) {}

    private final CaseRepository cases;
    private final GuardianRepository guardians;
    private final DemoSeedWriter writer;
    private final QuestionService questions;
    private final TherapistSummaryService therapist;
    private final TokenService tokens;
    private final WeekCalculator weeks;
    private final Clock clock;

    DemoSeedTestController(CaseRepository cases, GuardianRepository guardians, DemoSeedWriter writer,
                           QuestionService questions, TherapistSummaryService therapist, TokenService tokens,
                           WeekCalculator weeks, Clock clock) {
        this.cases = cases;
        this.guardians = guardians;
        this.writer = writer;
        this.questions = questions;
        this.therapist = therapist;
        this.tokens = tokens;
        this.weeks = weeks;
        this.clock = clock;
    }

    @PostMapping("/test/demo-seed")
    @ResponseStatus(HttpStatus.CREATED)
    Response create() {
        LocalDate today = weeks.today();
        String recoveryCode = tokens.newRecoveryCode();
        CaseEntity kase = cases.save(new CaseEntity(CaseService.OBSERVATION_SET, today.minusDays(35),
            Diagnosis.STROKE, PareticSide.RIGHT, VerbalDifficulty.OFTEN, today.plusDays(3),
            tokens.hash(recoveryCode), Instant.now(clock)));
        String token = tokens.newToken();
        Guardian daughter = guardians.save(new Guardian(kase.getId(), "딸", tokens.hash(token), Instant.now(clock)));
        writer.write(kase, daughter);
        questions.refresh(kase.getId());
        TherapistSummaryService.Link link = therapist.issueLink(kase);
        return new Response(kase.getId(), token, recoveryCode, link.url(), link.token());
    }
}
