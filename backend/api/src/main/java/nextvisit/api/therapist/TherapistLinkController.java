package nextvisit.api.therapist;

import nextvisit.api.auth.AuthContext;
import nextvisit.api.auth.CurrentGuardian;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TherapistLinkController {

    private final TherapistSummaryService service;

    public TherapistLinkController(TherapistSummaryService service) {
        this.service = service;
    }

    @PostMapping("/me/therapist-link")
    public TherapistSummaryService.Link issue(@CurrentGuardian AuthContext ctx) {
        return service.issueLink(ctx.kase());
    }
}
