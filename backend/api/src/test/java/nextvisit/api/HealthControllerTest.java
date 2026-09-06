package nextvisit.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void healthIsOk() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void flywayCreatedTables() {
        Integer n = jdbc.queryForObject(
            "select count(*) from information_schema.tables where lower(table_name) in ('cases','guardians','therapist_links','snapshots','question_cache')",
            Integer.class);
        org.junit.jupiter.api.Assertions.assertEquals(5, n);
    }

    @Test
    void unmappedPathReturnsNotFoundInTheApiErrorShape() throws Exception {
        mvc.perform(get("/definitely-not-a-route"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void wrongMethodOnARealPathReturnsMethodNotAllowedInTheApiErrorShape() throws Exception {
        mvc.perform(delete("/catalog"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }
}
