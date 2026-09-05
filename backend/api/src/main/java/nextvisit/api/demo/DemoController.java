package nextvisit.api.demo;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoController {

    private final DemoService service;

    public DemoController(DemoService service) {
        this.service = service;
    }

    @PostMapping("/demo")
    @ResponseStatus(HttpStatus.CREATED)
    public DemoResponse create() {
        return service.create();
    }
}
