package nextvisit.api.demo;

import nextvisit.api.auth.AuthContext;
import nextvisit.api.cases.CaseEntity;
import nextvisit.api.cases.CaseRepository;
import nextvisit.api.cases.CaseService;
import nextvisit.api.cases.MeResponse;
import nextvisit.api.cases.OnboardingRequest;
import nextvisit.api.cases.OnboardingResponse;
import nextvisit.api.common.CaseTimeline;
import nextvisit.api.common.ConflictException;
import nextvisit.api.snapshots.SnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class DemoService {

    private final CaseRepository cases;
    private final SnapshotRepository snapshots;
    private final CaseService caseService;
    private final CaseTimeline timeline;

    public DemoService(CaseRepository cases, SnapshotRepository snapshots, CaseService caseService,
                       CaseTimeline timeline) {
        this.cases = cases;
        this.snapshots = snapshots;
        this.caseService = caseService;
        this.timeline = timeline;
    }

    public OnboardingResponse create(OnboardingRequest req) {
        return caseService.onboardDemo(req);
    }

    public MeResponse advance(AuthContext ctx) {
        CaseEntity kase = cases.findByIdForUpdate(ctx.kase().getId()).orElseThrow();
        if (!kase.isDemoMode()) {
            throw new ConflictException("DEMO_ONLY", "데모 기록에서만 날짜를 진행할 수 있습니다");
        }
        int currentWeek = timeline.currentWeek(kase);
        if (snapshots.findByCaseIdAndWeek(kase.getId(), currentWeek).isEmpty()) {
            throw new ConflictException("DEMO_WEEK_NOT_RECORDED", "현재 주차 기록을 먼저 남겨주세요");
        }
        kase.advanceDemoWeek();
        cases.save(kase);
        return caseService.me(new AuthContext(ctx.guardian(), kase));
    }
}
