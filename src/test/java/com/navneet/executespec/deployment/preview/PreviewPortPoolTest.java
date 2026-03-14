package com.navneet.executespec.deployment.preview;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PreviewPortPool")
class PreviewPortPoolTest {

    @Mock DockerExecutor docker;
    @Mock ComposeExecutor compose;

    PreviewProperties props;
    PreviewPortPool pool;

    @BeforeEach
    void setUp() {
        props = new PreviewProperties();
        props.setPortRangeStart(10000);
        props.setPortRangeEnd(10002); // Small range: 3 ports (10000, 10001, 10002)

        when(docker.isDockerAvailable()).thenReturn(false); // skip reconcile in PostConstruct
        pool = new PreviewPortPool(props, docker, compose);
        pool.reconcile();
    }

    @Nested
    @DisplayName("Port allocation")
    class Allocation {

        @Test
        @DisplayName("First allocation returns range start")
        void firstAllocationReturnsStart() {
            int port = pool.allocate("run-1");
            assertThat(port).isEqualTo(10000);
        }

        @Test
        @DisplayName("Successive allocations return incremental ports")
        void successiveAllocations() {
            int p1 = pool.allocate("run-1");
            int p2 = pool.allocate("run-2");
            int p3 = pool.allocate("run-3");

            assertThat(p1).isEqualTo(10000);
            assertThat(p2).isEqualTo(10001);
            assertThat(p3).isEqualTo(10002);
        }

        @Test
        @DisplayName("Throws PreviewException when range is exhausted")
        void throwsWhenExhausted() {
            pool.allocate("run-1");
            pool.allocate("run-2");
            pool.allocate("run-3");

            assertThatThrownBy(() -> pool.allocate("run-4"))
                    .isInstanceOf(PreviewException.class)
                    .hasMessageContaining("No preview ports available");
        }

        @Test
        @DisplayName("activeCount reflects allocations")
        void activeCountReflectsAllocations() {
            assertThat(pool.activeCount()).isEqualTo(0);
            pool.allocate("run-1");
            assertThat(pool.activeCount()).isEqualTo(1);
            pool.allocate("run-2");
            assertThat(pool.activeCount()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("Port release")
    class Release {

        @Test
        @DisplayName("Released port can be re-allocated")
        void releasedPortCanBeReused() {
            int p1 = pool.allocate("run-1");
            pool.allocate("run-2");
            pool.allocate("run-3");

            // Range exhausted — release first port
            pool.release(p1);
            assertThat(pool.activeCount()).isEqualTo(2);

            // Now allocate again — should get the released port
            int p4 = pool.allocate("run-4");
            assertThat(p4).isEqualTo(p1);
        }

        @Test
        @DisplayName("Release unknown port is a no-op")
        void releaseUnknownPort() {
            pool.allocate("run-1");
            pool.release(99999);
            assertThat(pool.activeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("releaseByRunId frees all ports for that run")
        void releaseByRunId() {
            pool.allocate("run-1");
            pool.allocate("run-2");

            pool.releaseByRunId("run-1");
            assertThat(pool.activeCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("releaseByRunId for unknown runId is a no-op")
        void releaseByRunIdUnknown() {
            pool.allocate("run-1");
            pool.releaseByRunId("run-unknown");
            assertThat(pool.activeCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Reconciliation on startup")
    class Reconciliation {

        @Test
        @DisplayName("Reconciles orphan containers from previous JVM run")
        void reconcilesOrphans() {
            // Create a new pool with Docker available + orphan containers
            when(docker.isDockerAvailable()).thenReturn(true);
            when(docker.listPreviewContainers()).thenReturn(List.of("preview-abc123"));
            when(docker.inspectHostPort("preview-abc123", 5173)).thenReturn(10001);

            PreviewPortPool freshPool = new PreviewPortPool(props, docker, compose);
            freshPool.reconcile();

            // Port 10001 should be taken, so first allocation should be 10000
            int port = freshPool.allocate("run-new");
            assertThat(port).isEqualTo(10000);
            assertThat(freshPool.activeCount()).isEqualTo(2); // reconciled + new
        }

        @Test
        @DisplayName("Ignores containers with ports outside managed range")
        void ignoresOutOfRangePorts() {
            when(docker.isDockerAvailable()).thenReturn(true);
            when(docker.listPreviewContainers()).thenReturn(List.of("preview-xyz"));
            when(docker.inspectHostPort("preview-xyz", 5173)).thenReturn(9999); // outside range
            when(docker.inspectHostPort("preview-xyz", 3000)).thenReturn(-1);
            when(docker.inspectHostPort("preview-xyz", 8080)).thenReturn(-1);
            when(docker.inspectHostPort("preview-xyz", 8000)).thenReturn(-1);

            PreviewPortPool freshPool = new PreviewPortPool(props, docker, compose);
            freshPool.reconcile();

            assertThat(freshPool.activeCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("Handles Docker unavailable gracefully")
        void handlesDockerUnavailable() {
            when(docker.isDockerAvailable()).thenReturn(false);

            PreviewPortPool freshPool = new PreviewPortPool(props, docker, compose);
            freshPool.reconcile();

            assertThat(freshPool.activeCount()).isEqualTo(0);
        }
    }
}
