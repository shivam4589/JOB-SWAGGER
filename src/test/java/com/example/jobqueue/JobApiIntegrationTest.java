package com.example.jobqueue;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JobApiIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate rest;

    @Test
    void submitAndFetchJob() {
        Map<String, Object> body = Map.of(
                "type", "SEND_EMAIL",
                "payload", Map.of("to", "a@b.com"),
                "priority", 7);

        ResponseEntity<JsonNode> created = rest.postForEntity("/api/jobs", body, JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().get("status").asText()).isEqualTo("PENDING");
        assertThat(created.getBody().get("priority").asInt()).isEqualTo(7);

        String id = created.getBody().get("id").asText();
        ResponseEntity<JsonNode> fetched = rest.getForEntity("/api/jobs/" + id, JsonNode.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody().get("type").asText()).isEqualTo("SEND_EMAIL");
    }

    @Test
    void rejectsInvalidRequest() {
        Map<String, Object> body = Map.of("type", "", "priority", 99);
        ResponseEntity<JsonNode> res = rest.postForEntity("/api/jobs", body, JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void unknownJobReturns404() {
        ResponseEntity<JsonNode> res = rest.getForEntity(
                "/api/jobs/00000000-0000-0000-0000-000000000000", JsonNode.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
