package com.navneet.executespec.deployment.preview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for PreviewPortPool — allocates host ports for Docker Compose previews. */
@DisplayName("PreviewPortPool")
class PreviewPortPoolTest {

  PreviewPortPool pool;

  @BeforeEach
  void setUp() {
    var props = new PreviewProperties();
    props.setPortRangeStart(10000);
    props.setPortRangeEnd(10002); // small range for testing
    props.setUseEphemeralPorts(false); // deterministic range-based allocation for tests
    pool = new PreviewPortPool(props);
  }

  @Nested
  @DisplayName("Allocation")
  class Allocation {

    @Test
    @DisplayName("allocate returns first available port")
    void allocateReturnsPort() {
      int port = pool.allocate("run-1");
      assertThat(port).isEqualTo(10000);
    }

    @Test
    @DisplayName("allocate returns different ports for different runs")
    void allocateDifferentPorts() {
      int p1 = pool.allocate("run-1");
      int p2 = pool.allocate("run-2");
      assertThat(p1).isNotEqualTo(p2);
    }

    @Test
    @DisplayName("allocate is idempotent for same run")
    void allocateIdempotent() {
      int p1 = pool.allocate("run-1");
      int p2 = pool.allocate("run-1");
      assertThat(p1).isEqualTo(p2);
    }

    @Test
    @DisplayName("throws when all ports exhausted")
    void throwsWhenExhausted() {
      pool.allocate("run-1");
      pool.allocate("run-2");
      pool.allocate("run-3");
      assertThatThrownBy(() -> pool.allocate("run-4"))
          .isInstanceOf(PreviewException.class)
          .hasMessageContaining("No preview ports available");
    }
  }

  @Nested
  @DisplayName("Release")
  class Release {

    @Test
    @DisplayName("release frees port for reuse")
    void releaseFreesPort() {
      pool.allocate("run-1");
      pool.allocate("run-2");
      pool.allocate("run-3");
      pool.release("run-2");
      int reused = pool.allocate("run-4");
      assertThat(reused).isEqualTo(10001);
    }

    @Test
    @DisplayName("activeCount tracks allocations")
    void activeCount() {
      assertThat(pool.activeCount()).isEqualTo(0);
      pool.allocate("run-1");
      assertThat(pool.activeCount()).isEqualTo(1);
      pool.release("run-1");
      assertThat(pool.activeCount()).isEqualTo(0);
    }
  }
}
