package com.navneet.executespec.billing.lifecycle;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the pure state-machine logic in {@link TierLifecycleService}.
 *
 * <p>Instantiates the service with {@code null} repos because {@code
 * validateTransition} does not touch them. Tests that cover the repo +
 * audit-writing path live in {@code TierLifecycleServiceIntegrationTest}
 * (to be added in Day 2+ when the admin controller lands).
 */
class TierLifecycleServiceTest {

    private final TierLifecycleService svc =
            new TierLifecycleService(null, null, null);

    @Nested
    class AllowedTransitions {

        @Test
        void listedToHidden_isAllowed() {
            // just reaches the end without throwing
            svc.validateTransition(LifecycleState.LISTED, LifecycleState.HIDDEN, null);
        }

        @Test
        void hiddenToListed_isAllowed() {
            svc.validateTransition(LifecycleState.HIDDEN, LifecycleState.LISTED, Instant.now());
        }

        @Test
        void sunsettingToHidden_isAllowed() {
            svc.validateTransition(LifecycleState.SUNSETTING, LifecycleState.HIDDEN, Instant.now());
        }

        @Test
        void hiddenToSunsetting_isAllowed_after14Days() {
            Instant hiddenSince = Instant.now().minus(Duration.ofDays(15));
            svc.validateTransition(LifecycleState.HIDDEN, LifecycleState.SUNSETTING, hiddenSince);
        }
    }

    @Nested
    class RejectedTransitions {

        @Test
        void listedToSunsetting_isRejected() {
            assertThatThrownBy(() ->
                    svc.validateTransition(LifecycleState.LISTED, LifecycleState.SUNSETTING, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("LISTED → SUNSETTING");
        }

        @Test
        void sunsettingToListed_isRejected() {
            assertThatThrownBy(() ->
                    svc.validateTransition(LifecycleState.SUNSETTING, LifecycleState.LISTED, Instant.now()))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("SUNSETTING → LISTED");
        }

        @Test
        void sameStateTransition_isRejected() {
            assertThatThrownBy(() ->
                    svc.validateTransition(LifecycleState.LISTED, LifecycleState.LISTED, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Already in state");
        }

        @Test
        void hiddenToSunsetting_withNullChangedAt_isRejected() {
            assertThatThrownBy(() ->
                    svc.validateTransition(LifecycleState.HIDDEN, LifecycleState.SUNSETTING, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("observation window");
        }

        @Test
        void hiddenToSunsetting_before14Days_isRejected() {
            Instant hiddenSince = Instant.now().minus(Duration.ofDays(3));
            assertThatThrownBy(() ->
                    svc.validateTransition(LifecycleState.HIDDEN, LifecycleState.SUNSETTING, hiddenSince))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("day(s) remaining");
        }
    }

    @Nested
    class DbValueParsing {

        @Test
        void nullDbValue_parsesToListed() {
            assertThat(LifecycleState.fromDbValue(null)).isEqualTo(LifecycleState.LISTED);
        }

        @Test
        void unknownDbValue_parsesToListed() {
            assertThat(LifecycleState.fromDbValue("GARBAGE")).isEqualTo(LifecycleState.LISTED);
        }

        @Test
        void validDbValues_parseCorrectly() {
            assertThat(LifecycleState.fromDbValue("LISTED")).isEqualTo(LifecycleState.LISTED);
            assertThat(LifecycleState.fromDbValue("HIDDEN")).isEqualTo(LifecycleState.HIDDEN);
            assertThat(LifecycleState.fromDbValue("SUNSETTING")).isEqualTo(LifecycleState.SUNSETTING);
        }
    }
}
