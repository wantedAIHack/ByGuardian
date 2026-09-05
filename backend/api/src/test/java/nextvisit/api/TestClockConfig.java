package nextvisit.api;

import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** 테스트 프로필에서 시계를 고정·조작한다. 기본 2026-09-05. */
@Configuration
@Profile("test")
public class TestClockConfig {
    public static final LocalDate DEFAULT_TODAY = LocalDate.of(2026, 9, 5);

    @Bean
    @Primary
    public MutableClock testClock() {
        return new MutableClock(ZoneId.of("Asia/Seoul"), DEFAULT_TODAY);
    }
}
