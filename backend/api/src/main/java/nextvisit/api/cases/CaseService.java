package nextvisit.api.cases;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import nextvisit.api.auth.AuthContext;
import nextvisit.api.auth.Guardian;
import nextvisit.api.auth.GuardianRepository;
import nextvisit.api.auth.TokenService;
import nextvisit.api.common.Json;
import nextvisit.api.common.NotFoundException;
import nextvisit.api.common.WeekCalculator;
import nextvisit.api.snapshots.Snapshot;
import nextvisit.api.snapshots.SnapshotAssembler;
import nextvisit.api.snapshots.SnapshotBody;
import nextvisit.api.snapshots.SnapshotKind;
import nextvisit.api.snapshots.SnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CaseService {

    public static final String OBSERVATION_SET = "stroke";

    private final CaseRepository cases;
    private final GuardianRepository guardians;
    private final SnapshotRepository snapshots;
    private final SnapshotAssembler assembler;
    private final TokenService tokens;
    private final WeekCalculator weeks;
    private final Clock clock;
    private final Json json;

    public CaseService(CaseRepository cases, GuardianRepository guardians, SnapshotRepository snapshots,
                       SnapshotAssembler assembler, TokenService tokens, WeekCalculator weeks, Clock clock, Json json) {
        this.cases = cases;
        this.guardians = guardians;
        this.snapshots = snapshots;
        this.assembler = assembler;
        this.tokens = tokens;
        this.weeks = weeks;
        this.clock = clock;
        this.json = json;
    }

    public OnboardingResponse onboard(OnboardingRequest req) {
        LocalDate today = weeks.today();
        String recoveryCode = tokens.newRecoveryCode();
        CaseEntity kase = new CaseEntity(OBSERVATION_SET, today, req.diagnosis(), req.pareticSide(), req.verbalDifficulty(),
            req.nextVisitDate(), tokens.hash(recoveryCode), Instant.now(clock));

        // 기준선을 먼저 조립해 검증 실패 시 아무것도 저장하지 않는다
        SnapshotBody body = assembler.build(kase, req.baseline().items(), null,
            req.baseline().painSignal(), req.baseline().sleep(), req.baseline().freeNote());

        cases.save(kase);
        String token = tokens.newToken();
        Guardian g = guardians.save(new Guardian(kase.getId(), req.relation().trim(), tokens.hash(token), Instant.now(clock)));
        snapshots.save(new Snapshot(kase.getId(), 1, SnapshotKind.BASELINE, false, g.getId(), json.toJson(body), Instant.now(clock)));
        return new OnboardingResponse(kase.getId(), token, recoveryCode, 1);
    }

    @Transactional(readOnly = true)
    public MeResponse me(AuthContext ctx) {
        CaseEntity kase = ctx.kase();
        int week = weeks.currentWeek(kase.getStartDate());
        List<Snapshot> snaps = snapshots.findByCaseIdOrderByWeekAsc(kase.getId());
        Integer lastRecordedWeek = snaps.isEmpty() ? null : snaps.get(snaps.size() - 1).getWeek();
        boolean recordedThisWeek = lastRecordedWeek != null && lastRecordedWeek == week;
        // TherapistSummaryService의 Density와 같은 셈이다. 주차는 하나뿐이고 미래 주차는 저장될 수 없으므로
        // 마지막 스냅샷의 주차가 곧 이번 주 기록 여부다.
        int lastRecorded = lastRecordedWeek == null ? 0 : lastRecordedWeek;
        int totalWeeks = Math.max(1, Math.max(lastRecorded, recordedThisWeek ? week : week - 1));
        return new MeResponse(
            kase.getId(), ctx.guardian().getRelation(), weeks.today(), week,
            weeks.isFullRecheck(week), kase.signalsEnabled(), kase.handEnabled(),
            week >= 2, recordedThisWeek, lastRecordedWeek, kase.getNextVisitDate(),
            snaps.size(), totalWeeks);
    }

    /** ctx의 case 엔티티는 필터가 요청 시작 시 붙인 것이라 detach 상태일 수 있어, 여기서 다시 읽어 저장한다. */
    public MeResponse updateCase(AuthContext ctx, UpdateCaseRequest req) {
        CaseEntity kase = cases.findById(ctx.kase().getId())
            .orElseThrow(() -> new NotFoundException("케이스를 찾을 수 없습니다"));
        kase.setNextVisitDate(req.nextVisitDate());
        cases.save(kase);
        return me(new AuthContext(ctx.guardian(), kase));
    }

    public RecoverResponse recover(RecoverRequest req) {
        String code = req.recoveryCode().trim().toUpperCase();
        CaseEntity kase = cases.findByRecoveryCodeHash(tokens.hash(code))
            .orElseThrow(() -> new NotFoundException("복구 코드가 올바르지 않습니다"));
        String token = tokens.newToken();
        guardians.save(new Guardian(kase.getId(), req.relation().trim(), tokens.hash(token), Instant.now(clock)));
        return new RecoverResponse(token, kase.getId());
    }

    /**
     * 설정의 "복구 코드 다시 만들기". 적어둔 종이를 잃은 경우에만 쓴다.
     * ctx의 엔티티는 필터가 트랜잭션 밖에서 붙인 detach 상태라 반드시 id로 다시 읽고 쓴다.
     */
    public RecoveryCodeResponse reissueRecoveryCode(AuthContext ctx) {
        CaseEntity kase = cases.findById(ctx.kase().getId())
            .orElseThrow(() -> new NotFoundException("케이스를 찾을 수 없습니다"));
        String code = tokens.newRecoveryCode();
        kase.setRecoveryCodeHash(tokens.hash(code));
        cases.save(kase);
        return new RecoveryCodeResponse(code);
    }
}
