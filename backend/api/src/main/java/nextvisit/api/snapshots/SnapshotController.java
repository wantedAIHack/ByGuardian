package nextvisit.api.snapshots;

import nextvisit.api.auth.AuthContext;
import nextvisit.api.auth.CurrentGuardian;
import nextvisit.api.questions.QuestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SnapshotController {

    private static final Logger log = LoggerFactory.getLogger(SnapshotController.class);

    private final SnapshotService service;
    private final QuestionService questions;

    public SnapshotController(SnapshotService service, QuestionService questions) {
        this.service = service;
        this.questions = questions;
    }

    @PutMapping("/me/weeks/{week}")
    public WeeklyRecordResponse save(@CurrentGuardian AuthContext ctx, @PathVariable int week, @RequestBody WeeklyRecordRequest req) {
        WeeklyRecordResponse saved = service.saveWeekly(ctx, week, req);
        boolean refreshed = true;
        try {
            questions.refresh(ctx.kase().getId());
        } catch (RuntimeException e) {
            // 기록은 이미 커밋됐다. 질문 생성 실패로 보호자의 한 주를 버리지 않는다(README §4 층 1).
            log.warn("question refresh failed for case {} week {}", ctx.kase().getId(), week, e);
            refreshed = false;
        }
        return new WeeklyRecordResponse(saved.week(), saved.kind(), refreshed);
    }
}
