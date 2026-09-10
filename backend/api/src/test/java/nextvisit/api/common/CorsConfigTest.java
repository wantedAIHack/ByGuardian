package nextvisit.api.common;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "nextvisit.cors.allowed-origins=https://app.nextvisit.test")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CorsConfigTest {

    private static final String ALLOWED_ORIGIN = "https://app.nextvisit.test";

    @Autowired MockMvc mvc;

    @Test
    void allowsPatchPreflightFromTheExactConfiguredOrigin() throws Exception {
        mvc.perform(preflight(ALLOWED_ORIGIN, "PATCH", "X-Guardian-Token"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN))
            .andExpect(header().string("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,OPTIONS"))
            .andExpect(header().string("Access-Control-Allow-Headers", "X-Guardian-Token"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"))
            .andExpect(header().string("Access-Control-Max-Age", "3600"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://preview.nextvisit.test", "https://app.nextvisit.test.evil.invalid"})
    void rejectsUnconfiguredOriginPreflight(String origin) throws Exception {
        mvc.perform(preflight(origin, "PATCH", "X-Guardian-Token"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void rejectsDeletePreflight() throws Exception {
        mvc.perform(preflight(ALLOWED_ORIGIN, "DELETE", "X-Guardian-Token"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void rejectsAuthorizationHeaderPreflight() throws Exception {
        mvc.perform(preflight(ALLOWED_ORIGIN, "PATCH", "Authorization"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                " ",
                "not a uri",
                "//app.nextvisit.test",
                "ftp://app.nextvisit.test",
                "https://app.nextvisit.test/path",
                "https://app.nextvisit.test?query=true",
                "https://app.nextvisit.test#fragment",
                "https://user@app.nextvisit.test"
            })
    void rejectsInvalidConfiguredOrigin(String origin) {
        assertThatThrownBy(() -> new CorsConfig().corsFilter(List.of(origin)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder preflight(
            String origin, String method, String requestedHeaders) {
        return options("/health")
            .header("Origin", origin)
            .header("Access-Control-Request-Method", method)
            .header("Access-Control-Request-Headers", requestedHeaders);
    }
}
