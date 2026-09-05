package nextvisit.api.cases;

import jakarta.validation.Valid;
import nextvisit.api.auth.AuthContext;
import nextvisit.api.auth.CurrentGuardian;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CaseController {

    private final CaseService service;

    public CaseController(CaseService service) {
        this.service = service;
    }

    @PostMapping("/cases")
    @ResponseStatus(HttpStatus.CREATED)
    public OnboardingResponse onboard(@RequestBody @Valid OnboardingRequest req) {
        return service.onboard(req);
    }

    @GetMapping("/me")
    public MeResponse me(@CurrentGuardian AuthContext ctx) {
        return service.me(ctx);
    }

    @PostMapping("/guardians/recover")
    public RecoverResponse recover(@RequestBody @Valid RecoverRequest req) {
        return service.recover(req);
    }
}
