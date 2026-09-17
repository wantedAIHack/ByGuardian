package nextvisit.api.questions;

import jakarta.validation.Valid;
import java.util.List;
import nextvisit.api.auth.AuthContext;
import nextvisit.api.auth.CurrentGuardian;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PrepCardController {

    private final PrepCardService service;
    private final QuestionListService list;

    public PrepCardController(PrepCardService service, QuestionListService list) {
        this.service = service;
        this.list = list;
    }

    @GetMapping("/me/prep-card")
    public PrepCardDto card(@CurrentGuardian AuthContext ctx) {
        return service.card(ctx);
    }

    @PutMapping("/me/prep-card/extra")
    public List<String> extra(@CurrentGuardian AuthContext ctx, @RequestBody @Valid ExtraQuestionsRequest req) {
        return service.saveExtra(ctx, req.questions());
    }

    @PutMapping("/me/prep-card/questions")
    public PrepCardDto saveQuestions(@CurrentGuardian AuthContext ctx, @RequestBody @Valid SaveQuestionsRequest req) {
        list.save(ctx, req.items());
        return service.card(ctx);
    }

    @PostMapping("/me/prep-card/regenerate")
    public PrepCardDto regenerate(@CurrentGuardian AuthContext ctx) {
        list.regenerate(ctx);
        return service.card(ctx);
    }
}
