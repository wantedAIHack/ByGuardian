package nextvisit.api.demo;

import nextvisit.api.common.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
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

    @PostMapping("/demo")
    @ResponseStatus(HttpStatus.CREATED)
    public DemoResponse create() {
        if (!enabled) {
            throw new NotFoundException("데모가 꺼져 있습니다");
        }
        return service.create();
    }
}
