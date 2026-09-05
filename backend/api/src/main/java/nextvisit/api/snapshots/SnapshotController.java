package nextvisit.api.snapshots;

import nextvisit.api.auth.AuthContext;
import nextvisit.api.auth.CurrentGuardian;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SnapshotController {

    private final SnapshotService service;

    public SnapshotController(SnapshotService service) {
        this.service = service;
    }

    @PutMapping("/me/weeks/{week}")
    public WeeklyRecordResponse save(@CurrentGuardian AuthContext ctx, @PathVariable int week, @RequestBody WeeklyRecordRequest req) {
        return service.saveWeekly(ctx, week, req);
    }
}
