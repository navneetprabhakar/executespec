package com.navneet.agentteam.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TokenBudgetPolicy")
class TokenBudgetPolicyTest {

    private TokenBudgetPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new TokenBudgetPolicy();
    }

    @Test
    @DisplayName("Under budget → CONTINUE")
    void underBudget() {
        var action = policy.evaluate(2000, 8000, 14400);
        assertThat(action).isEqualTo(TokenBudgetPolicy.BudgetAction.CONTINUE);
    }

    @Test
    @DisplayName("At soft limit → COMPACT_AND_CONTINUE")
    void atSoftLimit() {
        var action = policy.evaluate(8000, 8000, 14400);
        assertThat(action).isEqualTo(TokenBudgetPolicy.BudgetAction.COMPACT_AND_CONTINUE);
    }

    @Test
    @DisplayName("Just over soft limit → COMPACT_AND_CONTINUE")
    void justOverSoftLimit() {
        var action = policy.evaluate(8001, 8000, 14400);
        assertThat(action).isEqualTo(TokenBudgetPolicy.BudgetAction.COMPACT_AND_CONTINUE);
    }

    @Test
    @DisplayName("At hard ceiling → FORCE_REPORT")
    void atHardCeiling() {
        var action = policy.evaluate(14400, 8000, 14400);
        assertThat(action).isEqualTo(TokenBudgetPolicy.BudgetAction.FORCE_REPORT);
    }

    @Test
    @DisplayName("Over hard ceiling → FORCE_REPORT")
    void overHardCeiling() {
        var action = policy.evaluate(15000, 8000, 14400);
        assertThat(action).isEqualTo(TokenBudgetPolicy.BudgetAction.FORCE_REPORT);
    }

    @Test
    @DisplayName("Zero tokens → CONTINUE")
    void zeroTokens() {
        var action = policy.evaluate(0, 8000, 14400);
        assertThat(action).isEqualTo(TokenBudgetPolicy.BudgetAction.CONTINUE);
    }

    @Test
    @DisplayName("Soft == hard ceiling → FORCE_REPORT on both")
    void softEqualsHard() {
        var action = policy.evaluate(10000, 10000, 10000);
        assertThat(action).isEqualTo(TokenBudgetPolicy.BudgetAction.FORCE_REPORT);
    }
}
