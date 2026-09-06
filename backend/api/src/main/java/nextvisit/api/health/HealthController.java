package nextvisit.api.health;

import java.util.Map;
import nextvisit.api.cases.CaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final CaseRepository cases;

    public HealthController(CaseRepository cases) {
        this.cases = cases;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        try {
            cases.count();
            return ResponseEntity.ok(Map.of("status", "ok", "db", "up"));
        } catch (Exception e) {
            log.error("헬스체크: DB 확인 실패", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "degraded", "db", "down"));
        }
    }
}
