package nextvisit.api.questions;

import jakarta.validation.Valid;
import java.util.List;
import nextvisit.api.auth.AuthContext;
import nextvisit.api.auth.CurrentGuardian;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PrepCardController {

    private final PrepCardService service;

    public PrepCardController(PrepCardService service) {
        this.service = service;
    }

    @GetMapping("/me/prep-card")
    public PrepCardDto card(@CurrentGuardian AuthContext ctx) {
        return service.card(ctx);
    }

    @PutMapping("/me/prep-card/extra")
    public List<String> extra(@CurrentGuardian AuthContext ctx, @RequestBody @Valid ExtraQuestionsRequest req) {
        return service.saveExtra(ctx, req.questions());
    }
}
