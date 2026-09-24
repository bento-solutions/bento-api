package com.bento.crm.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Shared Testcontainers + MockMvc scaffolding for controller integration tests, plus a helper
 * that signs up a fresh organization/admin user and returns a bearer token. Going through the
 * real signup -> login flow (rather than @WithMockUser) exercises the actual JWT/authority wiring,
 * which is what caught bugs like the broken /auth/refresh contract and the Partner response casing
 * mismatch during this audit.
 *
 * <p><b>Singleton container pattern.</b> The containers are {@code static} and started once from a
 * static initialiser, with no {@code @Testcontainers}/{@code @Container} lifecycle management.
 * Those annotations stop static containers in {@code afterAll} of <em>each</em> test class, but
 * Spring caches the application context (identical config across the suite) and keeps pointing it
 * at the now-dead port — which is why every class after the first failed with
 * "Could not open JPA EntityManager". Started once and left running (Ryuk reaps them when the JVM
 * exits), one Postgres and one Redis serve the whole suite and the cached context stays valid.
 *
 * <p>The schema is built by <b>Flyway running the real migrations</b>, then Hibernate is left on
 * {@code ddl-auto=validate} — the production setting. A mismatch between an entity and a migration
 * now fails a test instead of only surfacing at production startup.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.PostgreSQLDialect",
        "app.rate-limit.enabled=false",
        "JWT_SECRET=test-only-secret-key-not-for-production-use-minimum-32-bytes"
})
public abstract class IntegrationTestBase {

    public static final PostgreSQLContainer<?> postgres;
    public static final GenericContainer<?> redis;
    private static final Path fileStorage;

    static {
        // Match the production/dev image (docker-compose*.yml) so schema behaviour the tests
        // verify is the behaviour that will run in production.
        postgres = new PostgreSQLContainer<>("postgres:15-alpine")
                .withDatabaseName("crm_test_db")
                .withUsername("postgres")
                .withPassword("postgres");
        redis = new GenericContainer<>("redis:7-alpine")
                .withExposedPorts(6379);
        postgres.start();
        redis.start();
        // The default FILE_STORAGE_PATH (/data/uploads) only exists inside the container image.
        try {
            fileStorage = Files.createTempDirectory("crm-test-uploads");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Testcontainers assigns random host ports, so the datasource/redis coordinates baked into
    // application.yml (localhost:5432/6379) never match. Without this, Spring silently falls
    // back to those defaults and either hits a developer's local Postgres or fails outright --
    // which is what happened before this fix (see the audit note in AuthControllerTest).
    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("FILE_STORAGE_PATH", fileStorage::toString);
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    protected int testSequence = 0;

    @BeforeEach
    void resetSequence() {
        testSequence++;
    }

    /**
     * Signs up a brand-new organization with a fresh admin user (unique email per call so tests
     * don't collide) and returns the admin's access token.
     */
    protected String signUpAndLogin() throws Exception {
        String uniqueSuffix = System.nanoTime() + "-" + testSequence;
        String email = "admin-" + uniqueSuffix + "@example.com";
        String password = "TestPassword123!";

        String signupPayload = """
                {
                  "name": "Test Org %s",
                  "industry": "Technology",
                  "default_currency": "USD",
                  "timezone": "UTC",
                  "admin_email": "%s",
                  "admin_name": "Test Admin",
                  "admin_password": "%s"
                }
                """.formatted(uniqueSuffix, email, password);

        mockMvc.perform(post("/organizations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(signupPayload))
                .andReturn();

        String loginPayload = """
                {
                  "email": "%s",
                  "password": "%s"
                }
                """.formatted(email, password);

        String loginResponse = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(loginResponse).get("access_token").asText();
    }
}
