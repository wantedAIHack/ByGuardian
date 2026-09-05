package nextvisit.api.progress;

import java.util.List;
import nextvisit.api.auth.AuthContext;
import nextvisit.api.auth.CurrentGuardian;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ProgressController {

    private final ProgressService service;

    public ProgressController(ProgressService service) {
        this.service = service;
    }

    @GetMapping("/me/progress")
    public ProgressDto progress(@CurrentGuardian AuthContext ctx) {
        return service.progress(ctx);
    }

    @GetMapping("/me/trajectory")
    public List<TrajectoryDto> trajectory(@CurrentGuardian AuthContext ctx) {
        return service.trajectory(ctx);
    }
}
