package com.navneet.executespec.auth.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * RR-210 — a stale `app_user.org_id` pointer must not grant tenant-wide run visibility.
 *
 * <p>The old "safety fallback" treated a missing `org_member` row as sufficient to list every run
 * in the org, i.e. the denormalized pointer overrode the authoritative membership table that
 * revocation writes to.
 */
class RunVisibilitySpecOrphanMembershipTest {

  private final RunVisibilitySpec spec = new RunVisibilitySpec();

  @SuppressWarnings("unchecked")
  private String scopedColumn(AppUser user, OrgMember member) {
    Root<PipelineRun> root = mock(Root.class);
    CriteriaBuilder cb = mock(CriteriaBuilder.class);
    Path<Object> path = mock(Path.class);
    when(root.get(any(String.class))).thenReturn(path);
    when(cb.equal(any(), any(Object.class))).thenReturn(mock(Predicate.class));

    spec.forUser(user, member).toPredicate(root, null, cb);

    ArgumentCaptor<String> column = ArgumentCaptor.forClass(String.class);
    org.mockito.Mockito.verify(root).get(column.capture());
    return column.getValue();
  }

  private AppUser userInOrg() {
    AppUser u = new AppUser();
    u.setId("user-1");
    u.setGlobalRole("USER");
    u.setOrgId("org-1");
    return u;
  }

  @Test
  void orphanedOrgPointerSeesOnlyItsOwnRuns() {
    assertThat(scopedColumn(userInOrg(), null))
        .as("no org_member row => no tenant-wide visibility")
        .isEqualTo("createdByUserId");
  }

  @Test
  void unrecognizedOrgRoleAlsoFallsBackToOwnRuns() {
    OrgMember member = OrgMember.builder().orgId("org-1").userId("user-1").orgRole("GUEST").build();
    assertThat(scopedColumn(userInOrg(), member)).isEqualTo("createdByUserId");
  }

  @Test
  void activeDeveloperMembershipStillSeesTheOrg() {
    OrgMember member =
        OrgMember.builder().orgId("org-1").userId("user-1").orgRole("DEVELOPER").build();
    assertThat(scopedColumn(userInOrg(), member)).isEqualTo("orgId");
  }

  @Test
  @SuppressWarnings("unchecked")
  void projectMemberSeesOwnRunsPlusMemberProjectRuns() {
    // RR-212 — a collaborator with no org role must still discover the host project's runs, so the
    // spec OR-s own-runs with the accessible project ids rather than collapsing to own-runs only.
    Root<PipelineRun> root = mock(Root.class);
    CriteriaBuilder cb = mock(CriteriaBuilder.class);
    Path<Object> path = mock(Path.class);
    when(root.get(any(String.class))).thenReturn(path);
    when(cb.equal(any(), any(Object.class))).thenReturn(mock(Predicate.class));
    when(path.in(any(java.util.Collection.class))).thenReturn(mock(Predicate.class));
    when(cb.or(any(Predicate.class), any(Predicate.class))).thenReturn(mock(Predicate.class));

    spec.forUser(userInOrg(), null, java.util.List.of("proj-9")).toPredicate(root, null, cb);

    org.mockito.Mockito.verify(root).get("createdByUserId");
    org.mockito.Mockito.verify(root).get("projectId");
    org.mockito.Mockito.verify(cb).or(any(Predicate.class), any(Predicate.class));
  }

  @Test
  void superAdminIsUnfiltered() {
    AppUser admin = userInOrg();
    admin.setGlobalRole("SUPER_ADMIN");

    @SuppressWarnings("unchecked")
    Root<PipelineRun> root = mock(Root.class);
    CriteriaBuilder cb = mock(CriteriaBuilder.class);
    when(cb.conjunction()).thenReturn(mock(Predicate.class));

    spec.forUser(admin, null).toPredicate(root, null, cb);

    org.mockito.Mockito.verify(cb).conjunction();
    org.mockito.Mockito.verify(root, org.mockito.Mockito.never()).get(eq("orgId"));
  }
}
