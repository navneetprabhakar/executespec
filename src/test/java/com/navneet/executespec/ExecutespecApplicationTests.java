package com.navneet.executespec;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Full Spring context smoke test. Requires a real Postgres + Redis to be
 * reachable, which the default GitHub Actions runner does NOT provide
 * out of the box.
 *
 * <p>Tagged {@code integration} (matched by surefire's excludedGroups)
 * so it stays out of the fast-CI default run. Execute via
 * {@code mvn test -Pintegration} on a runner that has containers
 * available.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "spring.ai.anthropic.api-key=test-key",
    "spring.ai.openai.api-key=test-key",
    "agent.workspace=./test-workspace",
    "agent.state-db=./test-agent-state.db"
})
@Tag("integration")
class ExecutespecApplicationTests {

    @Test
    void contextLoads() {
        // Verify Spring context loads correctly
    }
}
