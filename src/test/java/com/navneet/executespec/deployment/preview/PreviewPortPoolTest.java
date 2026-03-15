package com.navneet.executespec.deployment.preview;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for PreviewPortPool (legacy no-op stub).
 * PreviewPortPool is deprecated after Cloud Run migration.
 * These tests verify the no-op behavior.
 */
@DisplayName("PreviewPortPool (legacy no-op)")
class PreviewPortPoolTest {

    PreviewPortPool pool;

    @BeforeEach
    void setUp() {
        pool = new PreviewPortPool();
    }

    @Nested
    @DisplayName("No-op behavior")
    class NoOpBehavior {

        @Test
        @DisplayName("allocate throws PreviewException (deprecated)")
        void allocateThrows() {
            assertThatThrownBy(() -> pool.allocate("run-1"))
                    .isInstanceOf(PreviewException.class)
                    .hasMessageContaining("deprecated");
        }

        @Test
        @DisplayName("release is a no-op")
        void releaseIsNoOp() {
            // Should not throw
            pool.release(10000);
        }

        @Test
        @DisplayName("releaseByRunId is a no-op")
        void releaseByRunIdIsNoOp() {
            // Should not throw
            pool.releaseByRunId("run-1");
        }

        @Test
        @DisplayName("activeCount returns 0")
        void activeCountReturnsZero() {
            assertThat(pool.activeCount()).isEqualTo(0);
        }
    }
}
