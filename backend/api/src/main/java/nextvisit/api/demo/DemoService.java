package nextvisit.api.demo;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** README §10 무대 시연 케이스. 오른쪽 마비, 표현이 어려운 어르신, 작성자는 딸. 오늘이 6주차. */
@Service
@Transactional
public class DemoService {

    public static final int WEEKS_BEFORE_TODAY = 35;
    public static final int VISIT_IN_DAYS = 3;

    private final CaseRepository cases;
    private final GuardianRepository guardians;
    private final DemoSeedWriter writer;
    private final QuestionService questions;
    private final TherapistSummaryService therapist;
    private final TokenService tokens;
    private final WeekCalculator weeks;
    private final Clock clock;

    public DemoService(CaseRepository cases, GuardianRepository guardians, DemoSeedWriter writer, QuestionService questions,
                       TherapistSummaryService therapist, TokenService tokens, WeekCalculator weeks, Clock clock) {
        this.cases = cases;
        this.guardians = guardians;
        this.writer = writer;
        this.questions = questions;
        this.therapist = therapist;
        this.tokens = tokens;
        this.weeks = weeks;
        this.clock = clock;
    }

    public DemoResponse create() {
        LocalDate today = weeks.today();
        String recoveryCode = tokens.newRecoveryCode();
        CaseEntity kase = cases.save(new CaseEntity(CaseService.OBSERVATION_SET, today.minusDays(WEEKS_BEFORE_TODAY),
            Diagnosis.STROKE, PareticSide.RIGHT, VerbalDifficulty.OFTEN, today.plusDays(VISIT_IN_DAYS),
            tokens.hash(recoveryCode), Instant.now(clock)));
        String token = tokens.newToken();
        Guardian daughter = guardians.save(new Guardian(kase.getId(), "딸", tokens.hash(token), Instant.now(clock)));
        writer.write(kase, daughter);
        questions.refresh(kase.getId());
        TherapistSummaryService.Link link = therapist.issueLink(kase);
        return new DemoResponse(kase.getId(), token, recoveryCode, link.url(), link.token());
    }
}
