package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/** Pins the state-parameterized database join used across the BUILD generation lifecycle. */
class WorkspaceGenerationAuthorityQueryContractTest {

  @Test
  void authorityJoinUsesTheCallersExactLifecycleState() throws Exception {
    Query query =
        WorkspaceGenerationAttemptRepository.class
            .getMethod("countCurrentAuthority", UUID.class, UUID.class, String.class)
            .getAnnotation(Query.class);

    assertThat(query).isNotNull();
    assertThat(query.value()).contains("a.state = :state").doesNotContain("a.state = 'MUTATING'");
  }
}
