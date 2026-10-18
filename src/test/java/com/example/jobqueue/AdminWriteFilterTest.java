package com.example.jobqueue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** With an admin password configured, reading stays open and every change needs the login. */
@Testcontainers
@SpringBootTest(properties = {"jobqueue.admin.user=admin", "jobqueue.admin.password=s3cret"})
@AutoConfigureMockMvc
class AdminWriteFilterTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    private static final String BODY = "{\"type\":\"T\",\"payload\":{}}";

    private static String basic(String credentials) {
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void readingNeedsNoLogin() throws Exception {
        mvc.perform(get("/api/jobs")).andExpect(status().isOk());
        mvc.perform(get("/api/stats")).andExpect(status().isOk());
    }

    @Test
    void changingThingsWithoutLoginIsRejected() throws Exception {
        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("WWW-Authenticate"));
        mvc.perform(delete("/api/jobs/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/schedules").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongOrMalformedCredentialsAreRejected() throws Exception {
        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", basic("admin:wrong")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", "Basic not-base64!!"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void correctLoginAllowsChanges() throws Exception {
        mvc.perform(post("/api/jobs").contentType(MediaType.APPLICATION_JSON).content(BODY)
                        .header("Authorization", basic("admin:s3cret")))
                .andExpect(status().isCreated());
        // an unknown job now reaches the controller (404) instead of being stopped at the door (401)
        mvc.perform(delete("/api/jobs/" + UUID.randomUUID())
                        .header("Authorization", basic("admin:s3cret")))
                .andExpect(status().isNotFound());
    }
}
