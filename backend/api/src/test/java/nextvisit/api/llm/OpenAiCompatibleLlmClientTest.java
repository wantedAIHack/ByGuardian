package nextvisit.api.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleLlmClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private MockRestServiceServer server;
    private OpenAiCompatibleLlmClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenAiCompatibleLlmClient(properties("access-id", "access-secret"),
            mapper, builder.build());
    }

    @Test
    void postsThePinnedContractAndReturnsOnlyAssistantContent() throws Exception {
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
            .andExpect(method(POST))
            .andExpect(request -> {
                assertThat(request.getHeaders().getFirst("Authorization")).isEqualTo("Bearer test-key");
                assertThat(request.getHeaders().getFirst("CF-Access-Client-Id")).isEqualTo("access-id");
                assertThat(request.getHeaders().getFirst("CF-Access-Client-Secret")).isEqualTo("access-secret");
                JsonNode body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                assertThat(body.get("model").asText()).isEqualTo("qwen3:4b-q8_0");
                assertThat(body.get("stream").asBoolean()).isFalse();
                assertThat(body.get("temperature").asDouble()).isEqualTo(0.1);
                assertThat(body.get("seed").asInt()).isZero();
                assertThat(body.get("max_tokens").asInt()).isEqualTo(512);
                assertThat(body.at("/response_format/type").asText()).isEqualTo("json_object");
                assertThat(body.at("/messages").size()).isEqualTo(2);
                assertThat(body.at("/messages/0/role").asText()).isEqualTo("system");
                assertThat(body.at("/messages/0/content").asText()).isEqualTo("system /no_think");
                assertThat(body.at("/messages/1/role").asText()).isEqualTo("user");
                assertThat(body.at("/messages/1/content").asText()).isEqualTo("{\"questions\":[]}");
            })
            .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"{\\\"questions\\\":[]}\"}}]}",
                MediaType.APPLICATION_JSON));

        String content = client.complete(new QuestionRewritePrompt.Prompt("system /no_think", "{\"questions\":[]}"));

        assertThat(content).isEqualTo("{\"questions\":[]}");
        server.verify();
    }

    /**
     * 2026-09-17 운영 재현: qwen3의 사고 과정이 표면 결합을 잘못 적용하거나(SURFACE_REWRITE)
     * 같은 판단을 반복하다 max_tokens=3000을 다 써서 빈 content(EMPTY_CONTENT)를 냈다.
     * /no_think와 chat_template_kwargs는 무시됐고 Ollama OpenAI 호환 엔드포인트의
     * reasoning_effort="none"만 사고를 껐다(reasoning 0자, 약 5초).
     */
    @Test
    void disablesReasoningAndSendsTheDemonstrationBeforeTheRealInput() throws Exception {
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
            .andExpect(request -> {
                JsonNode body = mapper.readTree(((MockClientHttpRequest) request).getBodyAsString());
                assertThat(body.get("reasoning_effort").asText()).isEqualTo("none");
                assertThat(body.at("/messages").size()).isEqualTo(4);
                assertThat(body.at("/messages/0/role").asText()).isEqualTo("system");
                assertThat(body.at("/messages/0/content").asText()).isEqualTo("system");
                assertThat(body.at("/messages/1/role").asText()).isEqualTo("user");
                assertThat(body.at("/messages/1/content").asText()).isEqualTo("example-user");
                assertThat(body.at("/messages/2/role").asText()).isEqualTo("assistant");
                assertThat(body.at("/messages/2/content").asText()).isEqualTo("example-assistant");
                assertThat(body.at("/messages/3/role").asText()).isEqualTo("user");
                assertThat(body.at("/messages/3/content").asText()).isEqualTo("real-user");
            })
            .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                MediaType.APPLICATION_JSON));

        client.complete(new QuestionRewritePrompt.Prompt("system",
            java.util.List.of(new QuestionRewritePrompt.Example("example-user", "example-assistant")),
            "real-user"));
        server.verify();
    }

    @Test
    void sendsNoCloudflareHeaderUnlessBothValuesExist() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenAiCompatibleLlmClient(properties("access-id", ""), mapper, builder.build());
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
            .andExpect(request -> {
                assertThat(request.getHeaders()).doesNotContainKey("CF-Access-Client-Id");
                assertThat(request.getHeaders()).doesNotContainKey("CF-Access-Client-Secret");
            })
            .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"{\\\"questions\\\":[]}\"}}]}",
                MediaType.APPLICATION_JSON));

        client.complete(new QuestionRewritePrompt.Prompt("system", "{\"questions\":[]}"));
        server.verify();
    }

    @Test
    void sendsNoAuthorizationHeaderWhenApiKeyIsBlank() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenAiCompatibleLlmClient(properties("", "", ""), mapper,
            builder.build());
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
            .andExpect(request ->
                assertThat(request.getHeaders()).doesNotContainKey("Authorization"))
            .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                MediaType.APPLICATION_JSON));

        client.complete(new QuestionRewritePrompt.Prompt("system", "user"));
        server.verify();
    }

    @Test
    void sendsNoCloudflareHeaderWhenOnlySecretExists() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new OpenAiCompatibleLlmClient(properties("test-key", "", "access-secret"),
            mapper, builder.build());
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
            .andExpect(request -> {
                assertThat(request.getHeaders()).doesNotContainKey("CF-Access-Client-Id");
                assertThat(request.getHeaders()).doesNotContainKey("CF-Access-Client-Secret");
            })
            .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}",
                MediaType.APPLICATION_JSON));

        client.complete(new QuestionRewritePrompt.Prompt("system", "user"));
        server.verify();
    }

    @Test
    void mapsHttpAndMalformedEnvelopeWithoutLeakingTheBody() {
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("RESPONSE_SENTINEL"));
        LlmClientException http = assertThrows(LlmClientException.class,
            () -> client.complete(new QuestionRewritePrompt.Prompt("system", "user")));
        assertThat(http.code()).isEqualTo(LlmFailureCode.HTTP_ERROR);
        assertThat(http.getMessage()).doesNotContain("RESPONSE_SENTINEL");

        RestClient.Builder secondBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(secondBuilder).build();
        client = new OpenAiCompatibleLlmClient(properties("", ""), mapper, secondBuilder.build());
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
            .andRespond(withSuccess("{\"choices\":[]}", MediaType.APPLICATION_JSON));
        LlmClientException empty = assertThrows(LlmClientException.class,
            () -> client.complete(new QuestionRewritePrompt.Prompt("system", "user")));
        assertThat(empty.code()).isEqualTo(LlmFailureCode.EMPTY_CONTENT);
    }

    @Test
    void mapsInvalidJsonAndTransportTimeoutToStableCodes() {
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
            .andRespond(withSuccess("not-json", MediaType.APPLICATION_JSON));
        LlmClientException invalid = assertThrows(LlmClientException.class,
            () -> client.complete(new QuestionRewritePrompt.Prompt("system", "user")));
        assertThat(invalid.code()).isEqualTo(LlmFailureCode.INVALID_RESPONSE);

        RestClient.Builder timeoutBuilder = RestClient.builder();
        server = MockRestServiceServer.bindTo(timeoutBuilder).build();
        client = new OpenAiCompatibleLlmClient(properties("", ""), mapper,
            timeoutBuilder.build());
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
            .andRespond(request -> {
                throw new org.springframework.web.client.ResourceAccessException(
                    "request timed out", new java.net.http.HttpTimeoutException("timed out"));
            });
        LlmClientException timeout = assertThrows(LlmClientException.class,
            () -> client.complete(new QuestionRewritePrompt.Prompt("system", "user")));
        assertThat(timeout.code()).isEqualTo(LlmFailureCode.TIMEOUT);
    }

    @Test
    void mapsTrailingGarbageToInvalidResponse() {
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
            .andRespond(withSuccess(
                "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]} trailing",
                MediaType.APPLICATION_JSON));

        LlmClientException invalid = assertThrows(LlmClientException.class,
            () -> client.complete(new QuestionRewritePrompt.Prompt("system", "user")));

        assertThat(invalid.code()).isEqualTo(LlmFailureCode.INVALID_RESPONSE);
    }

    private static LlmProperties properties(String accessId, String accessSecret) {
        return properties("test-key", accessId, accessSecret);
    }

    private static LlmProperties properties(String apiKey, String accessId, String accessSecret) {
        return new LlmProperties(true, URI.create("http://localhost:11434/v1"),
            "qwen3:4b-q8_0", apiKey, accessId, accessSecret,
            Duration.ofSeconds(3), Duration.ofSeconds(45), 3, 512);
    }
}
