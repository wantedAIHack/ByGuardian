package nextvisit.api.therapist;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TherapistController {

    private final TherapistSummaryService service;

    public TherapistController(TherapistSummaryService service) {
        this.service = service;
    }

    @GetMapping("/t/{token}")
    public TherapistSummaryDto summary(@PathVariable String token) {
        return service.summary(token);
    }
}
