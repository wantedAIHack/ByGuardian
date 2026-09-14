package nextvisit.api.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

class LlmConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withUserConfiguration(LlmConfiguration.class);

    @Test
    void defaultsAreSafeAndDoNotCreateAWorker() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            LlmProperties properties = context.getBean(LlmProperties.class);
            assertThat(properties.enabled()).isFalse();
            assertThat(properties.baseUrl()).isEqualTo(URI.create("http://localhost:11434/v1"));
            assertThat(properties.model()).isEqualTo("qwen3:4b-q4_K_M");
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(45));
            assertThat(properties.maxAttempts()).isEqualTo(3);
            assertThat(properties.maxOutputTokens()).isEqualTo(3000);
            assertThat(context.containsBean("llmTaskExecutor")).isFalse();
            assertThat(context).doesNotHaveBean(LlmClient.class);
            assertThat(context).doesNotHaveBean(RestClient.class);
        });
    }

    @Test
    void enabledCreatesOneBoundedWorker() {
        contextRunner.withPropertyValues("nextvisit.llm.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            ThreadPoolTaskExecutor executor = context.getBean(
                "llmTaskExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(1);
            assertThat(executor.getMaxPoolSize()).isEqualTo(1);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(32);
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
            assertThat(ReflectionTestUtils.getField(executor, "waitForTasksToCompleteOnShutdown"))
                .isEqualTo(true);
            assertThat(ReflectionTestUtils.getField(executor, "awaitTerminationMillis"))
                .isEqualTo(5_000L);
            assertThat(context).hasSingleBean(LlmClient.class);
            assertThat(context).hasSingleBean(RestClient.class);
        });
    }

    @Test
    void moreThanThreeAttemptsIsRejectedAtStartup() {
        contextRunner.withPropertyValues("nextvisit.llm.max-attempts=4").run(context ->
            assertThat(context).hasFailed());
    }
}
