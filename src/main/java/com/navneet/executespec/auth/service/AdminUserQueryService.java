package com.navneet.executespec.auth.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.navneet.executespec.auth.dto.UserDto;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * RR-012 — bounded, batched admin user listing extracted from {@code SuperAdminController} (keeps
 * the controller under the god-class ceiling). The listing used to run one org-membership query per
 * user over an unbounded set; this resolves every user's org role in a single batch query and lets
 * callers opt into a bounded page.
 */
@Service
@RequiredArgsConstructor
public class AdminUserQueryService {

  /** Default page size when a caller opts into pagination but omits a size. */
  private static final int DEFAULT_USER_PAGE_SIZE = 200;

  /** Hard ceiling on the page size to bound the response and query. */
  private static final int MAX_USER_PAGE_SIZE = 1000;

  private final AppUserRepository userRepo;
  private final OrgMemberRepository orgMemberRepo;

  /**
   * Lists active (non-deleted) users with their org role. Absent both params the full active list
   * is returned (unchanged behaviour); supplying either applies a bounded page.
   */
  public List<UserDto> listUsers(Integer page, Integer size) {
    List<AppUser> active;
    if (page != null || size != null) {
      int p = page == null ? 0 : Math.max(0, page);
      int s =
          size == null ? DEFAULT_USER_PAGE_SIZE : Math.min(Math.max(1, size), MAX_USER_PAGE_SIZE);
      active = userRepo.findAllActive(PageRequest.of(p, s));
    } else {
      active = userRepo.findAllActive();
    }

    List<String> userIds =
        active.stream()
            .filter(u -> u.getOrgId() != null)
            .map(AppUser::getId)
            .collect(Collectors.toList());
    Map<String, List<OrgMember>> membersByUser =
        userIds.isEmpty()
            ? Map.of()
            : orgMemberRepo.findByUserIdIn(userIds).stream()
                .collect(Collectors.groupingBy(OrgMember::getUserId));

    return active.stream()
        .map(
            u -> {
              OrgMember member = null;
              if (u.getOrgId() != null) {
                member =
                    membersByUser.getOrDefault(u.getId(), List.of()).stream()
                        .filter(m -> u.getOrgId().equals(m.getOrgId()))
                        .findFirst()
                        .orElse(null);
              }
              return UserDto.from(u, member);
            })
        .collect(Collectors.toList());
  }
}
