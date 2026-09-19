package nextvisit.api.demo;

import jakarta.validation.Valid;
import nextvisit.api.auth.AuthContext;
import nextvisit.api.auth.CurrentGuardian;
import nextvisit.api.cases.MeResponse;
import nextvisit.api.cases.OnboardingRequest;
import nextvisit.api.cases.OnboardingResponse;
import nextvisit.api.common.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoController {

    private final DemoService service;
    private final boolean enabled;

    public DemoController(DemoService service, @Value("${nextvisit.demo.enabled:true}") boolean enabled) {
        this.service = service;
        this.enabled = enabled;
    }

    @PostMapping("/demo/cases")
    @ResponseStatus(HttpStatus.CREATED)
    public OnboardingResponse create(@RequestBody @Valid OnboardingRequest req) {
        requireEnabled();
        return service.create(req);
    }

    @PostMapping("/me/demo/advance")
    public MeResponse advance(@CurrentGuardian AuthContext ctx) {
        requireEnabled();
        return service.advance(ctx);
    }

    private void requireEnabled() {
        if (!enabled) throw new NotFoundException("데모가 꺼져 있습니다");
    }
}
