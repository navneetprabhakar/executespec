package com.navneet.agentteam;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
    "spring.ai.anthropic.api-key=test-key",
    "spring.ai.openai.api-key=test-key",
    "agent.workspace=./test-workspace",
    "agent.state-db=./test-agent-state.db"
})
class AgentTeamApplicationTests {

    @Test
    void contextLoads() {
        // Verify Spring context loads correctly
    }
}
