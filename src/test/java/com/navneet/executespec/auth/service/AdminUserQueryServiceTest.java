package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.auth.dto.UserDto;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/** RR-012 — admin user listing resolves org roles in one batch query, not one query per user. */
class AdminUserQueryServiceTest {

  private final AppUserRepository userRepo = mock(AppUserRepository.class);
  private final OrgMemberRepository orgMemberRepo = mock(OrgMemberRepository.class);
  private final AdminUserQueryService service = new AdminUserQueryService(userRepo, orgMemberRepo);

  @Test
  void listUsersBatchesMembershipLookupAndResolvesRolesByOrg() {
    AppUser u1 = AppUser.builder().id("u1").orgId("o1").build();
    AppUser u2 = AppUser.builder().id("u2").build(); // no org
    AppUser u3 = AppUser.builder().id("u3").orgId("o1").build();
    when(userRepo.findAllActive()).thenReturn(List.of(u1, u2, u3));
    when(orgMemberRepo.findByUserIdIn(any()))
        .thenReturn(
            List.of(
                // u1 belongs to two orgs — only the row matching u1.orgId (o1) should be picked.
                OrgMember.builder()
                    .id("m0")
                    .userId("u1")
                    .orgId("o2")
                    .orgRole("PROJECT_VIEWER")
                    .build(),
                OrgMember.builder().id("m1").userId("u1").orgId("o1").orgRole("ORG_ADMIN").build(),
                OrgMember.builder()
                    .id("m2")
                    .userId("u3")
                    .orgId("o1")
                    .orgRole("DEVELOPER")
                    .build()));

    List<UserDto> body = service.listUsers(null, null);

    assertThat(body).extracting(UserDto::id).containsExactly("u1", "u2", "u3");
    assertThat(body.get(0).orgRole()).isEqualTo("ORG_ADMIN");
    assertThat(body.get(1).orgRole()).isNull();
    assertThat(body.get(2).orgRole()).isEqualTo("DEVELOPER");

    // The whole point: one batch query, zero per-user membership lookups.
    verify(orgMemberRepo, times(1)).findByUserIdIn(any());
    verify(orgMemberRepo, never()).findByOrgIdAndUserId(anyString(), anyString());
  }

  @Test
  void listUsersUsesBoundedPageWhenSizeSupplied() {
    when(userRepo.findAllActive(any(Pageable.class)))
        .thenReturn(List.of(AppUser.builder().id("u1").build()));

    List<UserDto> body = service.listUsers(0, 50);

    assertThat(body).extracting(UserDto::id).containsExactly("u1");
    // Paged path must not fall back to the unbounded query.
    verify(userRepo, never()).findAllActive();
    verify(userRepo).findAllActive(any(Pageable.class));
  }
}
