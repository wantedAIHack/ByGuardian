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
                assertThat(body.at("/messages/0/role").asText()).isEqualTo("system");
                assertThat(body.at("/messages/1/role").asText()).isEqualTo("user");
            })
            .andRespond(withSuccess("{\"choices\":[{\"message\":{\"content\":\"{\\\"questions\\\":[]}\"}}]}",
                MediaType.APPLICATION_JSON));

        String content = client.complete(new QuestionRewritePrompt.Prompt("system /no_think", "{\"questions\":[]}"));

        assertThat(content).isEqualTo("{\"questions\":[]}");
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

    private static LlmProperties properties(String accessId, String accessSecret) {
        return new LlmProperties(true, URI.create("http://localhost:11434/v1"),
            "qwen3:4b-q8_0", "test-key", accessId, accessSecret,
            Duration.ofSeconds(3), Duration.ofSeconds(45), 3, 512);
    }
}
