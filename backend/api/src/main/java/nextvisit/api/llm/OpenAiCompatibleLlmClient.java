package nextvisit.api.llm;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

public class OpenAiCompatibleLlmClient implements LlmClient {

    private final LlmProperties properties;
    private final ObjectMapper mapper;
    private final RestClient restClient;
    private final URI endpoint;

    public OpenAiCompatibleLlmClient(LlmProperties properties, ObjectMapper mapper,
                                     RestClient restClient) {
        this.properties = properties;
        this.mapper = mapper;
        this.restClient = restClient;
        this.endpoint = URI.create(stripTrailingSlash(properties.baseUrl().toString())
            + "/chat/completions");
    }

    /**
     * qwen3는 하이브리드 추론 모델이고 Ollama OpenAI 호환 엔드포인트는 /no_think와
     * chat_template_kwargs를 무시한다. reasoning_effort="none"만 사고를 끈다. 사고가 켜져 있으면
     * 표면 결합을 잘못 적용하거나 max_tokens를 사고에 다 써서 빈 content가 된다
     * (docs/qa/2026-09-17-llm-activation.md 9절).
     */
    static final String REASONING_EFFORT = "none";

    @Override
    public String complete(QuestionRewritePrompt.Prompt prompt) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", prompt.systemMessage()));
        for (QuestionRewritePrompt.Example example : prompt.examples()) {
            messages.add(new Message("user", example.userMessage()));
            messages.add(new Message("assistant", example.assistantMessage()));
        }
        messages.add(new Message("user", prompt.userMessage()));
        Request body = new Request(properties.model(), false, 0.1, 0,
            properties.maxOutputTokens(), new ResponseFormat("json_object"),
            REASONING_EFFORT, List.copyOf(messages));
        try {
            RestClient.RequestBodySpec request = restClient.post().uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON);
            if (StringUtils.hasText(properties.apiKey())) {
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.apiKey());
            }
            if (StringUtils.hasText(properties.cfAccessClientId())
                && StringUtils.hasText(properties.cfAccessClientSecret())) {
                request.header("CF-Access-Client-Id", properties.cfAccessClientId());
                request.header("CF-Access-Client-Secret", properties.cfAccessClientSecret());
            }
            String envelope = request.body(body).retrieve().body(String.class);
            return assistantContent(envelope);
        } catch (RestClientResponseException e) {
            throw new LlmClientException(LlmFailureCode.HTTP_ERROR);
        } catch (ResourceAccessException e) {
            LlmFailureCode code = causedByTimeout(e)
                ? LlmFailureCode.TIMEOUT : LlmFailureCode.CONNECTION_ERROR;
            throw new LlmClientException(code, e);
        } catch (RestClientException e) {
            throw new LlmClientException(LlmFailureCode.CONNECTION_ERROR, e);
        }
    }

    private String assistantContent(String envelope) {
        if (envelope == null || envelope.isBlank()) {
            throw new LlmClientException(LlmFailureCode.EMPTY_CONTENT);
        }
        try {
            JsonNode root = mapper.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .readValue(envelope);
            JsonNode choices = root.get("choices");
            if (choices == null || !choices.isArray() || choices.isEmpty()) {
                throw new LlmClientException(LlmFailureCode.EMPTY_CONTENT);
            }
            JsonNode content = choices.get(0).path("message").path("content");
            if (!content.isTextual() || content.textValue().isBlank()) {
                throw new LlmClientException(LlmFailureCode.EMPTY_CONTENT);
            }
            return content.textValue();
        } catch (JsonProcessingException e) {
            throw new LlmClientException(LlmFailureCode.INVALID_RESPONSE);
        }
    }

    private static boolean causedByTimeout(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof HttpTimeoutException || current instanceof SocketTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private record Request(
        String model,
        boolean stream,
        double temperature,
        int seed,
        @JsonProperty("max_tokens") int maxTokens,
        @JsonProperty("response_format") ResponseFormat responseFormat,
        @JsonProperty("reasoning_effort") String reasoningEffort,
        List<Message> messages
    ) {}

    private record ResponseFormat(String type) {}

    private record Message(String role, String content) {}
}
