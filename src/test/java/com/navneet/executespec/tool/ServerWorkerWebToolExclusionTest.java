package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * RR-548. The compatibility {@code web_fetch}/{@code web_search} were advertised to ordinary
 * server-side workers, because the lean-toolset flag that would have removed them is default-OFF
 * and only the small unconditional deny set was applied. A cloud worker runs against a cloned
 * workspace and has no legitimate egress need, so the exclusion must be unconditional — not
 * flag-dependent.
 */
class ServerWorkerWebToolExclusionTest {

  @Test
  void serverWorkersNeverSeeNetworkEgressTools() {
    assertThat(ToolRegistry.SERVER_WORKER_EXCLUDED_TOOLS).contains("web_fetch", "web_search");
  }

  @Test
  void localHandsProfileStillCarriesThemBehindTheGuardedFetchSeam() {
    assertThat(ToolRegistry.REMOTE_LOCAL_HANDS_WORKER_TOOLS).contains("web_fetch", "web_search");
    assertThat(ToolRegistry.LEAN_WORKER_TOOLS).doesNotContain("web_fetch", "web_search");
  }
}
