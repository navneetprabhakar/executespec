package com.navneet.executespec.repositoryintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

class RepositoryTargetContractTest {

  @Test
  void authorityTypesCannotBeConstructedOutsideTheirPackage() {
    assertThat(RepositoryTarget.class.getDeclaredConstructors())
        .allSatisfy(
            constructor -> assertThat(Modifier.isPublic(constructor.getModifiers())).isFalse());
    assertThat(RepositoryAccessContext.class.getDeclaredConstructors())
        .allSatisfy(
            constructor -> assertThat(Modifier.isPublic(constructor.getModifiers())).isFalse());
  }

  @Test
  void derivesOnlyAuthorityIdentityFromQuery() {
    RepositoryAccessContext access = new RepositoryAccessContext("actor-1", "tenant-1");
    RepositorySnapshotId snapshot =
        new RepositorySnapshotId(
            "project-1",
            "repo-1",
            "abc123",
            null,
            "inventory",
            "native",
            1,
            Instant.EPOCH,
            RepositoryFreshness.CURRENT);
    RepositoryQuery query =
        new RepositoryQuery(
            "project-1",
            "repo-1",
            "Widget",
            Set.of(RepositoryCapability.EXACT_TEXT),
            "src",
            "*.java",
            snapshot,
            QueryBudget.DEFAULT,
            false,
            access);

    RepositoryTarget target = RepositoryTarget.from(query);

    assertThat(target).isEqualTo(new RepositoryTarget("project-1", "repo-1", access));
  }

  @Test
  void rejectsMissingAuthorityIdentity() {
    RepositoryAccessContext access = new RepositoryAccessContext("actor-1", "tenant-1");

    assertThatThrownBy(() -> new RepositoryTarget(null, "repo-1", access))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("projectId");
    assertThatThrownBy(() -> new RepositoryTarget("project-1", " ", access))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("repositoryId");
    assertThatThrownBy(() -> new RepositoryTarget("project-1", "repo-1", null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("accessContext");
  }
}
