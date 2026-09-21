package com.company.modernizer;

import com.company.modernizer.config.ModernizerProperties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
// Boot 4 relocated this from org.springframework.boot.test.autoconfigure.web.servlet
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 0 smoke tests: the context loads, properties bind with their documented defaults, and the
 * health endpoint serves.
 *
 * <p>The defaults asserted here are the two safety-relevant ones - AI egress off, and a non-empty
 * scan allowlist - so an accidental change to either fails the build rather than shipping quietly.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ModernizerApplicationTests {

    @Autowired
    private ModernizerProperties properties;

    @Autowired
    private MockMvcTester mvc;

    @Test
    @DisplayName("Spring context loads")
    void contextLoads() {
        assertThat(properties).isNotNull();
    }

    @Test
    @DisplayName("AI egress is disabled by default")
    void aiIsDisabledByDefault() {
        assertThat(properties.ai().enabled()).isFalse();
        assertThat(properties.ai().provider()).isEqualTo("anthropic");
        assertThat(properties.ai().model()).isEqualTo("claude-opus-5");
    }

    @Test
    @DisplayName("Scanner limits bind, and the allowlist is non-empty so the demo fixture is scannable")
    void scanLimitsBind() {
        assertThat(properties.scan().hasAllowedRoots()).isTrue();
        assertThat(properties.scan().maxFiles()).isEqualTo(20000);
        assertThat(properties.scan().maxFileSize().toBytes()).isEqualTo(1024L * 1024L);
        assertThat(properties.scan().excludedDirectories()).contains("target", ".git");
    }

    @Test
    @DisplayName("GET /api/v1/health reports UP and the effective AI configuration")
    void healthEndpointReportsConfiguration() {
        assertThat(mvc.get().uri("/api/v1/health"))
                .hasStatusOk()
                .bodyJson()
                .extractingPath("$.status").isEqualTo("UP");

        assertThat(mvc.get().uri("/api/v1/health"))
                .bodyJson()
                .extractingPath("$.ai.model").isEqualTo("claude-opus-5");
    }
}
