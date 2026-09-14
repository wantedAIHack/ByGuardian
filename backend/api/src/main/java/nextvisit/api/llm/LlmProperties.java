package nextvisit.api.llm;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "nextvisit.llm")
public record LlmProperties(
    @DefaultValue("false") boolean enabled,
    @DefaultValue("http://localhost:11434/v1") URI baseUrl,
    @NotBlank @DefaultValue("qwen3:4b-q4_K_M") String model,
    @DefaultValue("ollama") String apiKey,
    @DefaultValue("") String cfAccessClientId,
    @DefaultValue("") String cfAccessClientSecret,
    @DefaultValue("3s") Duration connectTimeout,
    @DefaultValue("45s") Duration readTimeout,
    @Min(1) @Max(3) @DefaultValue("3") int maxAttempts,
    @Min(1) @DefaultValue("3000") int maxOutputTokens
) {}
