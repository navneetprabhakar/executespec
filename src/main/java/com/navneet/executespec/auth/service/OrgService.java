package com.navneet.executespec.auth.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.controller.OrgController.SeatsAdjustResponse;
import com.navneet.executespec.auth.dto.CreateOrgRequest;
import com.navneet.executespec.auth.dto.OrgDto;
import com.navneet.executespec.auth.dto.OrgMemberDto;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.OrgMember;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgMemberRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.billing.entity.BillingSubscription;
import com.navneet.executespec.billing.gateway.StripePaymentGateway;
import com.navneet.executespec.billing.repository.BillingSubscriptionRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrgService {

  private final OrgRepository orgRepo;
  private final OrgMemberRepository orgMemberRepo;
  private final AppUserRepository userRepo;
  private final PaymentAuditService paymentAuditService;
  private final ActivityAuditService auditService;
  private final MailService mailService;
  private final BillingSubscriptionRepository subRepo;
  private final StripePaymentGateway stripeGateway;

  /** TEAM tier self-serve seat range. Orgs above this go to ENTERPRISE. */
  private static final int TEAM_MIN_SEATS = 2;

  private static final int TEAM_MAX_SEATS = 20;
  private final ObjectMapper mapper = new ObjectMapper();

  @Transactional
  public OrgDto createOrg(String creatorUserId, CreateOrgRequest req) {
    AppUser creator =
        userRepo
            .findById(creatorUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

    if ("FREE".equals(creator.getTier())) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Only paid tiers (Pro, Team, Max, Enterprise) can create an org");
    }
    if (creator.getOrgId() != null) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "You are already a member of an org. Leave it first.");
    }

    String domainPatterns = String.join(",", req.domains()).toLowerCase();
    String orgId = UUID.randomUUID().toString();

    Org org =
        Org.builder()
            .id(orgId)
            .name(req.name())
            .domainPatterns(domainPatterns)
            .tier(creator.getTier())
            .status("ACTIVE")
            .adminEmail(req.adminEmail())
            .createdByUserId(creatorUserId)
            .build();
    orgRepo.save(org);

    // Associate creator as ORG_ADMIN
    orgMemberRepo.save(
        OrgMember.builder()
            .id(UUID.randomUUID().toString())
            .orgId(orgId)
            .userId(creatorUserId)
            .orgRole("ORG_ADMIN")
            .build());

    creator.setOrgId(orgId);
    creator.setUpdatedAt(Instant.now());
    userRepo.save(creator);

    paymentAuditService.record(
        "ORG", orgId, "PLAN_ASSIGNED", null, org.getTier(), creatorUserId, "Org created");

    auditOrgEvent(
        "ORG_CREATED", orgId, creatorUserId, Map.of("name", req.name(), "tier", creator.getTier()));

    log.info("Org created: {} ({}) by {}", req.name(), orgId, creatorUserId);
    return OrgDto.from(org);
  }

  public OrgDto getOrg(String orgId) {
    return orgRepo
        .findById(orgId)
        .map(OrgDto::from)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
  }

  @Transactional
  public OrgDto updateOrg(String orgId, String actorId, String name, List<String> domains) {
    Org org =
        orgRepo
            .findById(orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
    String oldName = org.getName();
    String oldDomains = org.getDomainPatterns();
    if (name != null && !name.isBlank()) org.setName(name);
    if (domains != null && !domains.isEmpty()) {
      org.setDomainPatterns(String.join(",", domains).toLowerCase());
    }
    org.setUpdatedAt(Instant.now());
    OrgDto result = OrgDto.from(orgRepo.save(org));

    auditOrgEvent(
        "ORG_UPDATED",
        "ORG",
        orgId,
        orgId,
        actorId,
        Map.of("name", String.valueOf(oldName), "domains", String.valueOf(oldDomains)),
        Map.of("name", org.getName(), "domains", org.getDomainPatterns()));

    return result;
  }

  public List<OrgMemberDto> listMembers(String orgId) {
    return orgMemberRepo.findByOrgId(orgId).stream()
        .map(
            member -> {
              AppUser user = userRepo.findById(member.getUserId()).orElse(null);
              if (user == null) return null;
              return OrgMemberDto.from(user, member);
            })
        .filter(dto -> dto != null)
        .collect(Collectors.toList());
  }

  @Transactional
  public void removeMember(String orgId, String userId, String performedBy) {
    OrgMember member =
        orgMemberRepo
            .findByOrgIdAndUserId(orgId, userId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));

    // Cannot remove last ORG_ADMIN
    if ("ORG_ADMIN".equals(member.getOrgRole())) {
      long adminCount =
          orgMemberRepo.findByOrgId(orgId).stream()
              .filter(m -> "ORG_ADMIN".equals(m.getOrgRole()))
              .count();
      if (adminCount <= 1) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Cannot remove the last ORG_ADMIN");
      }
    }

    String removedRole = member.getOrgRole();
    orgMemberRepo.delete(member);

    // Clear user's org_id
    userRepo
        .findById(userId)
        .ifPresent(
            user -> {
              user.setOrgId(null);
              user.setUpdatedAt(Instant.now());
              userRepo.save(user);
            });

    auditOrgEvent(
        "ORG_MEMBER_REMOVED",
        "ORG_MEMBER",
        userId,
        orgId,
        performedBy,
        Map.of("userId", userId, "role", removedRole),
        null);

    log.info("Removed user {} from org {} by {}", userId, orgId, performedBy);
  }

  @Transactional
  public void changeMemberRole(String orgId, String userId, String newRole, String actorId) {
    OrgMember member =
        orgMemberRepo
            .findByOrgIdAndUserId(orgId, userId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
    String oldRole = member.getOrgRole();
    member.setOrgRole(newRole);
    orgMemberRepo.save(member);

    auditOrgEvent(
        "ORG_MEMBER_ROLE_CHANGED",
        "ORG_MEMBER",
        userId,
        orgId,
        actorId,
        Map.of("role", oldRole),
        Map.of("role", newRole));
  }

  public List<OrgDto> listAllOrgs() {
    return orgRepo.findAllActive().stream().map(OrgDto::from).collect(Collectors.toList());
  }

  @Transactional
  public void changeOrgStatus(String orgId, String status, String performedBy) {
    Org org =
        orgRepo
            .findById(orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
    String oldStatus = org.getStatus();
    org.setStatus(status);
    org.setUpdatedAt(Instant.now());
    orgRepo.save(org);

    auditOrgEvent(
        "ORG_STATUS_CHANGED",
        "ORG",
        orgId,
        orgId,
        performedBy,
        Map.of("status", oldStatus),
        Map.of("status", status));
  }

  /**
   * Suspend or restore a member within an org. Guards: cannot suspend self; cannot suspend the last
   * ORG_ADMIN.
   */
  @Transactional
  public void suspendMember(
      String orgId, String targetUserId, boolean suspend, String performedBy) {
    orgMemberRepo
        .findByOrgIdAndUserId(orgId, targetUserId)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found in org"));

    if (targetUserId.equals(performedBy)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot suspend yourself");
    }

    AppUser target =
        userRepo
            .findById(targetUserId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

    String oldStatus = target.getStatus();
    target.setStatus(suspend ? "SUSPENDED" : "ACTIVE");
    target.setUpdatedAt(Instant.now());
    userRepo.save(target);

    auditOrgEvent(
        suspend ? "ORG_MEMBER_SUSPENDED" : "ORG_MEMBER_RESTORED",
        "ORG_MEMBER",
        targetUserId,
        orgId,
        performedBy,
        Map.of("status", oldStatus),
        Map.of("status", target.getStatus()));

    // Notify user on suspension
    if (suspend && !"SUSPENDED".equals(oldStatus)) {
      mailService.sendSuspensionEmail(target.getEmail(), target.getFirstName());
    }

    log.info("User {} {} by {}", targetUserId, suspend ? "suspended" : "restored", performedBy);
  }

  /**
   * Create an org on behalf of a super admin invite flow. Does NOT require the creator to be
   * PRO/ENTERPRISE — SA bypasses that guard.
   */
  @Transactional
  public Org createOrgAsAdmin(String name, String adminUserId) {
    String orgId = UUID.randomUUID().toString();
    Org org =
        Org.builder()
            .id(orgId)
            .name(name)
            .domainPatterns("")
            .tier("PRO")
            .status("ACTIVE")
            .createdByUserId(adminUserId)
            .build();
    orgRepo.save(org);
    paymentAuditService.record(
        "ORG", orgId, "PLAN_ASSIGNED", null, "PRO", adminUserId, "Created by super admin");

    auditOrgEvent(
        "ORG_CREATED",
        orgId,
        adminUserId,
        Map.of("name", name, "tier", "PRO", "createdByAdmin", true));

    log.info("Org '{}' ({}) created by super admin {}", name, orgId, adminUserId);
    return org;
  }

  /**
   * V162 — bootstrap a personal org for a fresh self-registered user so downstream flows (project
   * create, sandbox provisioning) that assume orgId-non-null work for solo users.
   *
   * <p>Why a separate method:
   *
   * <ul>
   *   <li>{@link #createOrg} blocks FREE-tier with HTTP 403 ("Only paid tiers can create an org") —
   *       a deliberate billing rule we don't want to weaken. Personal orgs are not a
   *       business-billable scope.
   *   <li>{@link #createOrgAsAdmin} requires SUPER_ADMIN context and audits as "Created by super
   *       admin" — wrong actor.
   * </ul>
   *
   * <p>Idempotent: if the user already has an orgId, return their org unchanged (callers don't have
   * to pre-check). The user is set as {@code ORG_ADMIN} of their personal org so they pass the
   * role-gate on {@code POST /api/projects} via {@link
   * com.navneet.executespec.auth.security.AppUserDetails#getAuthorities} which composes {@code
   * ROLE_USER + ROLE_ORG_ADMIN}.
   *
   * <p>Personal orgs are flagged {@code is_personal=true} so org switchers, invitation flows, and
   * billing seat counts can filter them out (one-line follow-up in those callers, intentionally NOT
   * in this hotfix to keep the blast radius minimal).
   *
   * <p>If called for a user who already has {@code orgId} set (e.g. they were invited into a real
   * org by accept-invite), this is a no-op — we don't replace a real-org membership with a
   * personal-org membership.
   */
  @Transactional
  public Org bootstrapPersonalOrg(String userId) {
    AppUser user =
        userRepo
            .findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

    // Idempotency: already a member of an org (personal or real) → return that one.
    if (user.getOrgId() != null) {
      return orgRepo
          .findById(user.getOrgId())
          .orElseGet(
              () -> {
                // Stale reference — user.orgId points at a deleted org. Re-bootstrap.
                log.warn(
                    "User {} had stale orgId={} — re-bootstrapping personal org",
                    userId,
                    user.getOrgId());
                user.setOrgId(null);
                userRepo.save(user);
                return doBootstrapPersonal(user);
              });
    }

    return doBootstrapPersonal(user);
  }

  private Org doBootstrapPersonal(AppUser user) {
    String orgId = UUID.randomUUID().toString();
    String displayName =
        (user.getFirstName() == null || user.getFirstName().isBlank()
                ? user.getEmail().split("@")[0]
                : user.getFirstName())
            + "'s workspace";

    Org org =
        Org.builder()
            .id(orgId)
            .name(displayName)
            .domainPatterns("") // personal orgs don't enforce domains
            .tier(user.getTier()) // mirror the user's tier (FREE on signup)
            .status("ACTIVE")
            .adminEmail(user.getEmail())
            .createdByUserId(user.getId())
            .isPersonal(true)
            .build();
    orgRepo.save(org);

    // Make the user ORG_ADMIN so they pass the project-create role gate.
    orgMemberRepo.save(
        OrgMember.builder()
            .id(UUID.randomUUID().toString())
            .orgId(orgId)
            .userId(user.getId())
            .orgRole("ORG_ADMIN")
            .build());

    user.setOrgId(orgId);
    user.setUpdatedAt(Instant.now());
    userRepo.save(user);

    auditOrgEvent(
        "ORG_CREATED",
        orgId,
        user.getId(),
        Map.of("name", displayName, "tier", user.getTier(), "isPersonal", true));

    log.info(
        "Personal org bootstrapped: userId={} orgId={} tier={}",
        user.getId(),
        orgId,
        user.getTier());
    return org;
  }

  /** Update per-org run limit override. Pass null to clear the override (use tier default). */
  @Transactional
  public OrgDto updateRunLimitOverride(String orgId, Integer limit, String actorId) {
    Org org =
        orgRepo
            .findById(orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
    Integer oldLimit = org.getMonthlyRunLimitOverride();
    org.setMonthlyRunLimitOverride(limit);
    org.setUpdatedAt(Instant.now());
    OrgDto result = OrgDto.from(orgRepo.save(org));

    auditOrgEvent(
        "ORG_RUN_LIMIT_CHANGED",
        "ORG",
        orgId,
        orgId,
        actorId,
        Map.of("limit", String.valueOf(oldLimit)),
        Map.of("limit", String.valueOf(limit)));

    return result;
  }

  /**
   * V198 (2026-05-16) — toggle the per-org "external sharing enabled" flag. When this is true,
   * SUPER_ADMIN or ORG_ADMIN of this org may invite users from any email domain (gmail, outlook,
   * etc.) as cross-org members on a project. Default is false; existing tenants are opt-in.
   *
   * <p>Gated to ORG_ADMIN of this org / SUPER_ADMIN at the controller via {@code @PreAuthorize}.
   * The service method emits an audit row so flips can be retraced for compliance.
   */
  @Transactional
  public OrgDto updateExternalSharing(String orgId, boolean enabled, String actorId) {
    Org org =
        orgRepo
            .findById(orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
    boolean before = org.isExternalSharingEnabled();
    org.setExternalSharingEnabled(enabled);
    org.setUpdatedAt(Instant.now());
    OrgDto result = OrgDto.from(orgRepo.save(org));

    auditOrgEvent(
        "ORG_EXTERNAL_SHARING_CHANGED",
        "ORG",
        orgId,
        orgId,
        actorId,
        Map.of("enabled", before),
        Map.of("enabled", enabled));

    return result;
  }

  /** Toggle the api_keys_provided flag for an enterprise org. */
  @Transactional
  public OrgDto updateApiKeysProvided(String orgId, boolean provided, String actorId) {
    Org org =
        orgRepo
            .findById(orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
    boolean oldProvided = org.isApiKeysProvided();
    org.setApiKeysProvided(provided);
    org.setUpdatedAt(Instant.now());
    OrgDto result = OrgDto.from(orgRepo.save(org));

    auditOrgEvent(
        "ORG_API_KEYS_FLAG_CHANGED",
        "ORG",
        orgId,
        orgId,
        actorId,
        Map.of("provided", oldProvided),
        Map.of("provided", provided));

    return result;
  }

  // ── Org-level AI provider key management ─────────────────────────────────

  @Transactional
  public OrgDto updateAiKeySettings(
      String orgId,
      String anthropicKey,
      String openAiKey,
      String geminiKey,
      String openRouterKey,
      String actorId) {
    Org org =
        orgRepo
            .findById(orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
    // Blank = "leave unchanged", not "clear" — the settings UI saves one provider
    // at a time and sends only the edited field, so an unconditional set would wipe
    // the other keys. Each key is overwritten only when a non-blank value arrives.
    if (anthropicKey != null && !anthropicKey.isBlank()) {
      org.setAnthropicApiKey(anthropicKey.trim());
    }
    if (openAiKey != null && !openAiKey.isBlank()) {
      org.setOpenAiApiKey(openAiKey.trim());
    }
    if (geminiKey != null && !geminiKey.isBlank()) {
      org.setGeminiApiKey(geminiKey.trim());
    }
    // OpenRouter (gateway) is Enterprise-only — reject the key on lower tiers so the provider can
    // never be offered (AvailableProviderResolver treats key-present as the Enterprise signal).
    String openRouter = blankToNull(openRouterKey);
    if (openRouter != null) {
      if (!"ENTERPRISE".equalsIgnoreCase(org.getTier())) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN, "OpenRouter is an Enterprise-tier feature");
      }
      org.setOpenRouterApiKey(openRouter);
    }
    org.setUpdatedAt(Instant.now());
    log.info(
        "Org {} updated AI key settings — anthropic={}, openai={}, gemini={}, openrouter={}",
        orgId,
        org.getAnthropicApiKey() != null,
        org.getOpenAiApiKey() != null,
        org.getGeminiApiKey() != null,
        org.getOpenRouterApiKey() != null);
    OrgDto result = OrgDto.from(orgRepo.save(org));

    auditOrgEvent(
        "ORG_AI_KEYS_CHANGED",
        orgId,
        actorId,
        Map.of(
            "anthropic", org.getAnthropicApiKey() != null,
            "openai", org.getOpenAiApiKey() != null,
            "gemini", org.getGeminiApiKey() != null));

    return result;
  }

  // ── Key source mode & provider restriction settings ─────────────────────

  private static final java.util.Set<String> VALID_KEY_SOURCE_MODES =
      java.util.Set.of("SYSTEM", "OWN_KEYS", "HYBRID");

  private static final java.util.Set<String> VALID_PROVIDERS =
      java.util.Set.of("anthropic", "openai", "gemini");

  @Transactional
  public OrgDto updateKeySourceSettings(
      String orgId, String keySourceMode, java.util.List<String> enabledProviders, String actorId) {
    Org org =
        orgRepo
            .findById(orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));

    if (keySourceMode != null) {
      if (!VALID_KEY_SOURCE_MODES.contains(keySourceMode)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Invalid keySourceMode: " + keySourceMode + ". Must be SYSTEM, OWN_KEYS, or HYBRID.");
      }
      org.setKeySourceMode(keySourceMode);
    }

    if (enabledProviders != null) {
      // Validate and normalize
      for (String p : enabledProviders) {
        if (!VALID_PROVIDERS.contains(p.toLowerCase())) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "Invalid provider: " + p + ". Must be anthropic, openai, or google.");
        }
      }
      if (enabledProviders.isEmpty()) {
        org.setEnabledProviders(null); // empty list = all providers
      } else {
        org.setEnabledProviders(
            enabledProviders.stream()
                .map(String::toLowerCase)
                .distinct()
                .collect(Collectors.joining(",")));
      }
    }

    org.setUpdatedAt(Instant.now());
    log.info(
        "Org {} updated key source settings — mode={}, providers={}",
        orgId,
        org.getKeySourceMode(),
        org.getEnabledProviders());
    OrgDto result = OrgDto.from(orgRepo.save(org));

    auditOrgEvent(
        "ORG_KEY_SOURCE_CHANGED",
        orgId,
        actorId,
        Map.of(
            "mode", String.valueOf(org.getKeySourceMode()),
            "providers", String.valueOf(org.getEnabledProviders())));

    return result;
  }

  // ── Org-level JIRA credential management ──────────────────────────────

  @Transactional
  public OrgDto updateJiraSettings(
      String orgId, String baseUrl, String email, String apiToken, String actorId) {
    Org org =
        orgRepo
            .findById(orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
    org.setJiraBaseUrl(blankToNull(baseUrl));
    org.setJiraEmail(blankToNull(email));
    org.setJiraApiToken(blankToNull(apiToken));
    org.setUpdatedAt(Instant.now());
    log.info(
        "Org {} updated JIRA settings — baseUrl={}, hasToken={}",
        orgId,
        org.getJiraBaseUrl(),
        org.getJiraApiToken() != null);
    OrgDto result = OrgDto.from(orgRepo.save(org));

    auditOrgEvent(
        "ORG_JIRA_SETTINGS_CHANGED",
        orgId,
        actorId,
        Map.of(
            "hasToken",
            org.getJiraApiToken() != null,
            "baseUrl",
            String.valueOf(org.getJiraBaseUrl())));

    return result;
  }

  // ── Org-level Confluence credential management ──────────────────────────

  @Transactional
  public OrgDto updateConfluenceSettings(
      String orgId, String baseUrl, String email, String apiToken, String actorId) {
    Org org =
        orgRepo
            .findById(orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
    org.setConfluenceBaseUrl(blankToNull(baseUrl));
    org.setConfluenceEmail(blankToNull(email));
    org.setConfluenceApiToken(blankToNull(apiToken));
    org.setUpdatedAt(Instant.now());
    log.info(
        "Org {} updated Confluence settings — baseUrl={}, hasToken={}",
        orgId,
        org.getConfluenceBaseUrl(),
        org.getConfluenceApiToken() != null);
    OrgDto result = OrgDto.from(orgRepo.save(org));

    auditOrgEvent(
        "ORG_CONFLUENCE_SETTINGS_CHANGED",
        orgId,
        actorId,
        Map.of(
            "hasToken",
            org.getConfluenceApiToken() != null,
            "baseUrl",
            String.valueOf(org.getConfluenceBaseUrl())));

    return result;
  }

  // ── Integration status helpers ──────────────────────────────────────────

  /**
   * Returns the list of integration source keys (e.g. "JIRA", "CONFLUENCE") that have fully
   * configured credentials at the org level.
   */
  public List<String> getEnabledIntegrations(String orgId) {
    if (orgId == null) return List.of();
    Org org = orgRepo.findById(orgId).orElse(null);
    if (org == null) return List.of();
    var result = new java.util.ArrayList<String>();
    if (present(org.getJiraBaseUrl())
        && present(org.getJiraEmail())
        && present(org.getJiraApiToken())) {
      result.add("JIRA");
    }
    if (present(org.getConfluenceBaseUrl())
        && present(org.getConfluenceEmail())
        && present(org.getConfluenceApiToken())) {
      result.add("CONFLUENCE");
    }
    return result;
  }

  private static boolean present(String s) {
    return s != null && !s.isBlank();
  }

  private static String blankToNull(String s) {
    return (s != null && !s.isBlank()) ? s.strip() : null;
  }

  private void auditOrgEvent(String eventType, String orgId, String actorId, Object newValue) {
    String json;
    try {
      json = mapper.writeValueAsString(newValue);
    } catch (Exception e) {
      json = String.valueOf(newValue);
    }
    auditService.record(
        ActivityAudit.builder()
            .eventType(eventType)
            .entityType("ORG")
            .entityId(orgId)
            .orgId(orgId)
            .actorId(actorId)
            .newValue(json)
            .build());
  }

  private void auditOrgEvent(
      String eventType,
      String entityType,
      String entityId,
      String orgId,
      String actorId,
      Object oldValue,
      Object newValue) {
    String oldJson = null;
    String newJson = null;
    try {
      if (oldValue != null) oldJson = mapper.writeValueAsString(oldValue);
      if (newValue != null) newJson = mapper.writeValueAsString(newValue);
    } catch (Exception e) {
      if (oldValue != null) oldJson = String.valueOf(oldValue);
      if (newValue != null) newJson = String.valueOf(newValue);
    }
    var builder =
        ActivityAudit.builder()
            .eventType(eventType)
            .entityType(entityType)
            .entityId(entityId)
            .orgId(orgId)
            .actorId(actorId);
    if (oldJson != null) builder.oldValue(oldJson);
    if (newJson != null) builder.newValue(newJson);
    auditService.record(builder.build());
  }

  @Transactional
  public void changeOrgTier(String orgId, String newTier, String performedBy) {
    Org org =
        orgRepo
            .findById(orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
    String oldTier = org.getTier();
    org.setTier(newTier);
    // V143 product_id flip retired (V179, single-surface retirement
    // 2026-05-06) — there's no surface to swap to.
    org.setUpdatedAt(Instant.now());
    orgRepo.save(org);
    paymentAuditService.record("ORG", orgId, "PLAN_UPGRADED", oldTier, newTier, performedBy, null);

    auditOrgEvent(
        "ORG_TIER_CHANGED",
        "ORG",
        orgId,
        orgId,
        performedBy,
        Map.of("tier", oldTier),
        Map.of("tier", newTier));
  }

  /**
   * v2.6.0 — adjust seats on a TEAM-tier org. Pushes to Stripe (prorated), writes {@code
   * BillingSubscription.totalSeats} + {@code Org.seat_count} locally so the UI reflects the new
   * count immediately; the webhook arriving later is idempotent.
   *
   * <p>Validates:
   *
   * <ul>
   *   <li>{@code newSeats ∈ [TEAM_MIN_SEATS, TEAM_MAX_SEATS]} (2-20)
   *   <li>New seats is not below current active member count
   *   <li>Org is on TEAM tier and has an active subscription
   * </ul>
   */
  @Transactional
  public SeatsAdjustResponse adjustSeats(String orgId, int newSeats, String performedBy) {
    Org org =
        orgRepo
            .findById(orgId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));

    if (!"TEAM".equals(org.getTier())) {
      throw new ResponseStatusException(
          HttpStatus.PAYMENT_REQUIRED,
          "Seat adjustment is only available for TEAM tier. Upgrade first.");
    }
    if (newSeats < TEAM_MIN_SEATS || newSeats > TEAM_MAX_SEATS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "newSeats must be between "
              + TEAM_MIN_SEATS
              + " and "
              + TEAM_MAX_SEATS
              + ". Contact sales for larger orgs.");
    }

    int activeMembers = (int) orgMemberRepo.countByOrgId(orgId);
    if (newSeats < activeMembers) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Cannot reduce seats to "
              + newSeats
              + " — org has "
              + activeMembers
              + " active members. Remove members first, then reduce seats.");
    }

    int previousSeats = org.getSeatCount();
    if (previousSeats == newSeats) {
      return new SeatsAdjustResponse(
          previousSeats,
          newSeats,
          activeMembers,
          "No change — org already has " + newSeats + " seats.");
    }

    // Find the active TEAM subscription + push seat quantity to the gateway.
    BillingSubscription teamSub =
        subRepo.findActiveByTier("TEAM").stream()
            .filter(s -> orgId.equals(s.getOrgId()))
            .findFirst()
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.PAYMENT_REQUIRED,
                        "No active TEAM subscription found for this org. Complete checkout"
                            + " first."));

    String gatewayName =
        teamSub.getPaymentGateway() != null ? teamSub.getPaymentGateway() : teamSub.getGateway();
    if ("stripe".equalsIgnoreCase(gatewayName) && teamSub.getGatewaySubscriptionId() != null) {
      stripeGateway.updateSubscriptionQuantity(teamSub.getGatewaySubscriptionId(), newSeats);
    } else if ("razorpay".equalsIgnoreCase(gatewayName)) {
      // M-011 (Wave 3) — Razorpay's subscription model doesn't support
      // mid-cycle quantity changes the way Stripe does. Surface a 501
      // pointing the admin at the manual workflow rather than silently
      // diverging the org-side seat_count from the actual billing.
      log.warn(
          "[M-011] Razorpay seat adjust requested but unsupported in-band — "
              + "org={} gatewaySub={} requestedSeats={} → 501 with manual-flow pointer",
          orgId,
          teamSub.getGatewaySubscriptionId(),
          newSeats);
      throw new ResponseStatusException(
          HttpStatus.NOT_IMPLEMENTED,
          "Razorpay subscriptions don't support mid-cycle seat changes. "
              + "Email billing@executespec.ai with your org id ("
              + orgId
              + ") "
              + "and the new seat count ("
              + newSeats
              + "); ops will issue a "
              + "plan-swap at your next billing cycle. See docs/payment-history.md "
              + "for the full workflow.");
    } else {
      log.warn(
          "Seat adjust: unknown gateway '{}' for org {} — gateway push skipped.",
          gatewayName,
          orgId);
    }

    // Write locally — webhook will arrive later and be idempotent.
    teamSub.setTotalSeats(newSeats);
    subRepo.save(teamSub);

    org.setSeatCount(newSeats);
    org.setUpdatedAt(Instant.now());
    orgRepo.save(org);

    auditOrgEvent(
        "ORG_SEATS_ADJUSTED",
        "ORG",
        orgId,
        orgId,
        performedBy,
        Map.of("seats", previousSeats),
        Map.of("seats", newSeats, "activeMembers", activeMembers));

    log.info(
        "Org seats adjusted: orgId={} {}→{} activeMembers={} by userId={}",
        orgId,
        previousSeats,
        newSeats,
        activeMembers,
        performedBy);

    String message =
        newSeats > previousSeats
            ? "Added "
                + (newSeats - previousSeats)
                + " seat(s). You can now invite up to "
                + newSeats
                + " members."
            : "Reduced to "
                + newSeats
                + " seat(s). Prorated credit will appear on your next invoice.";
    return new SeatsAdjustResponse(previousSeats, newSeats, activeMembers, message);
  }
}
