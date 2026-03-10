package com.navneet.executespec.auth.service;

import com.navneet.executespec.auth.dto.*;
import com.navneet.executespec.auth.entity.*;
import com.navneet.executespec.auth.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrgService {

    private final OrgRepository orgRepo;
    private final OrgMemberRepository orgMemberRepo;
    private final AppUserRepository userRepo;
    private final PaymentAuditService paymentAuditService;

    @Transactional
    public OrgDto createOrg(String creatorUserId, CreateOrgRequest req) {
        AppUser creator = userRepo.findById(creatorUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        if ("FREE".equals(creator.getTier())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only PRO or ENTERPRISE users can create an org");
        }
        if (creator.getOrgId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You are already a member of an org. Leave it first.");
        }

        String domainPatterns = String.join(",", req.domains()).toLowerCase();
        String orgId = UUID.randomUUID().toString();

        Org org = Org.builder()
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
        orgMemberRepo.save(OrgMember.builder()
                .id(UUID.randomUUID().toString())
                .orgId(orgId)
                .userId(creatorUserId)
                .orgRole("ORG_ADMIN")
                .build());

        creator.setOrgId(orgId);
        creator.setUpdatedAt(Instant.now());
        userRepo.save(creator);

        paymentAuditService.record("ORG", orgId, "PLAN_ASSIGNED",
                null, org.getTier(), creatorUserId, "Org created");

        log.info("Org created: {} ({}) by {}", req.name(), orgId, creatorUserId);
        return OrgDto.from(org);
    }

    public OrgDto getOrg(String orgId) {
        return orgRepo.findById(orgId)
                .map(OrgDto::from)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
    }

    @Transactional
    public OrgDto updateOrg(String orgId, String name, List<String> domains) {
        Org org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
        if (name != null && !name.isBlank()) org.setName(name);
        if (domains != null && !domains.isEmpty()) {
            org.setDomainPatterns(String.join(",", domains).toLowerCase());
        }
        org.setUpdatedAt(Instant.now());
        return OrgDto.from(orgRepo.save(org));
    }

    public List<OrgMemberDto> listMembers(String orgId) {
        return orgMemberRepo.findByOrgId(orgId).stream()
                .map(member -> {
                    AppUser user = userRepo.findById(member.getUserId()).orElse(null);
                    if (user == null) return null;
                    return OrgMemberDto.from(user, member);
                })
                .filter(dto -> dto != null)
                .collect(Collectors.toList());
    }

    @Transactional
    public void removeMember(String orgId, String userId, String performedBy) {
        OrgMember member = orgMemberRepo.findByOrgIdAndUserId(orgId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));

        // Cannot remove last ORG_ADMIN
        if ("ORG_ADMIN".equals(member.getOrgRole())) {
            long adminCount = orgMemberRepo.findByOrgId(orgId).stream()
                    .filter(m -> "ORG_ADMIN".equals(m.getOrgRole())).count();
            if (adminCount <= 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Cannot remove the last ORG_ADMIN");
            }
        }

        orgMemberRepo.delete(member);

        // Clear user's org_id
        userRepo.findById(userId).ifPresent(user -> {
            user.setOrgId(null);
            user.setUpdatedAt(Instant.now());
            userRepo.save(user);
        });
        log.info("Removed user {} from org {} by {}", userId, orgId, performedBy);
    }

    @Transactional
    public void changeMemberRole(String orgId, String userId, String newRole) {
        OrgMember member = orgMemberRepo.findByOrgIdAndUserId(orgId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
        member.setOrgRole(newRole);
        orgMemberRepo.save(member);
    }

    public List<OrgDto> listAllOrgs() {
        return orgRepo.findAllActive().stream().map(OrgDto::from).collect(Collectors.toList());
    }

    @Transactional
    public void changeOrgStatus(String orgId, String status, String performedBy) {
        Org org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
        org.setStatus(status);
        org.setUpdatedAt(Instant.now());
        orgRepo.save(org);
    }

    /**
     * Suspend or restore a member within an org.
     * Guards: cannot suspend self; cannot suspend the last ORG_ADMIN.
     */
    @Transactional
    public void suspendMember(String orgId, String targetUserId, boolean suspend, String performedBy) {
        orgMemberRepo.findByOrgIdAndUserId(orgId, targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found in org"));

        if (targetUserId.equals(performedBy)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot suspend yourself");
        }

        AppUser target = userRepo.findById(targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        target.setStatus(suspend ? "SUSPENDED" : "ACTIVE");
        target.setUpdatedAt(Instant.now());
        userRepo.save(target);
        log.info("User {} {} by {}", targetUserId, suspend ? "suspended" : "restored", performedBy);
    }

    /**
     * Create an org on behalf of a super admin invite flow.
     * Does NOT require the creator to be PRO/ENTERPRISE — SA bypasses that guard.
     */
    @Transactional
    public Org createOrgAsAdmin(String name, String adminUserId) {
        String orgId = UUID.randomUUID().toString();
        Org org = Org.builder()
                .id(orgId)
                .name(name)
                .domainPatterns("")
                .tier("PRO")
                .status("ACTIVE")
                .createdByUserId(adminUserId)
                .build();
        orgRepo.save(org);
        paymentAuditService.record("ORG", orgId, "PLAN_ASSIGNED", null, "PRO", adminUserId, "Created by super admin");
        log.info("Org '{}' ({}) created by super admin {}", name, orgId, adminUserId);
        return org;
    }

    /** Update per-org run limit override. Pass null to clear the override (use tier default). */
    @Transactional
    public OrgDto updateRunLimitOverride(String orgId, Integer limit) {
        Org org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
        org.setMonthlyRunLimitOverride(limit);
        org.setUpdatedAt(Instant.now());
        return OrgDto.from(orgRepo.save(org));
    }

    /** Toggle the api_keys_provided flag for an enterprise org. */
    @Transactional
    public OrgDto updateApiKeysProvided(String orgId, boolean provided) {
        Org org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
        org.setApiKeysProvided(provided);
        org.setUpdatedAt(Instant.now());
        return OrgDto.from(orgRepo.save(org));
    }

    // ── Org-level AI provider key management ─────────────────────────────────

    @Transactional
    public OrgDto updateAiKeySettings(String orgId, String anthropicKey, String openAiKey, String geminiKey) {
        Org org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
        org.setAnthropicApiKey(blankToNull(anthropicKey));
        org.setOpenAiApiKey(blankToNull(openAiKey));
        org.setGeminiApiKey(blankToNull(geminiKey));
        org.setUpdatedAt(Instant.now());
        log.info("Org {} updated AI key settings — anthropic={}, openai={}, gemini={}",
                orgId,
                org.getAnthropicApiKey() != null,
                org.getOpenAiApiKey() != null,
                org.getGeminiApiKey() != null);
        return OrgDto.from(orgRepo.save(org));
    }

    // ── Key source mode & provider restriction settings ─────────────────────

    private static final java.util.Set<String> VALID_KEY_SOURCE_MODES =
            java.util.Set.of("SYSTEM", "OWN_KEYS", "HYBRID");

    private static final java.util.Set<String> VALID_PROVIDERS =
            java.util.Set.of("anthropic", "openai", "google");

    @Transactional
    public OrgDto updateKeySourceSettings(String orgId, String keySourceMode, java.util.List<String> enabledProviders) {
        Org org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));

        if (keySourceMode != null) {
            if (!VALID_KEY_SOURCE_MODES.contains(keySourceMode)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Invalid keySourceMode: " + keySourceMode + ". Must be SYSTEM, OWN_KEYS, or HYBRID.");
            }
            org.setKeySourceMode(keySourceMode);
        }

        if (enabledProviders != null) {
            // Validate and normalize
            for (String p : enabledProviders) {
                if (!VALID_PROVIDERS.contains(p.toLowerCase())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
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
        log.info("Org {} updated key source settings — mode={}, providers={}",
                orgId, org.getKeySourceMode(), org.getEnabledProviders());
        return OrgDto.from(orgRepo.save(org));
    }

    // ── Org-level JIRA credential management ──────────────────────────────

    @Transactional
    public OrgDto updateJiraSettings(String orgId, String baseUrl, String email, String apiToken) {
        Org org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
        org.setJiraBaseUrl(blankToNull(baseUrl));
        org.setJiraEmail(blankToNull(email));
        org.setJiraApiToken(blankToNull(apiToken));
        org.setUpdatedAt(Instant.now());
        log.info("Org {} updated JIRA settings — baseUrl={}, hasToken={}",
                orgId, org.getJiraBaseUrl(), org.getJiraApiToken() != null);
        return OrgDto.from(orgRepo.save(org));
    }

    // ── Org-level Confluence credential management ──────────────────────────

    @Transactional
    public OrgDto updateConfluenceSettings(String orgId, String baseUrl, String email, String apiToken) {
        Org org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
        org.setConfluenceBaseUrl(blankToNull(baseUrl));
        org.setConfluenceEmail(blankToNull(email));
        org.setConfluenceApiToken(blankToNull(apiToken));
        org.setUpdatedAt(Instant.now());
        log.info("Org {} updated Confluence settings — baseUrl={}, hasToken={}",
                orgId, org.getConfluenceBaseUrl(), org.getConfluenceApiToken() != null);
        return OrgDto.from(orgRepo.save(org));
    }

    // ── Integration status helpers ──────────────────────────────────────────

    /**
     * Returns the list of integration source keys (e.g. "JIRA", "CONFLUENCE") that have
     * fully configured credentials at the org level.
     */
    public List<String> getEnabledIntegrations(String orgId) {
        if (orgId == null) return List.of();
        Org org = orgRepo.findById(orgId).orElse(null);
        if (org == null) return List.of();
        var result = new java.util.ArrayList<String>();
        if (present(org.getJiraBaseUrl()) && present(org.getJiraEmail()) && present(org.getJiraApiToken())) {
            result.add("JIRA");
        }
        if (present(org.getConfluenceBaseUrl()) && present(org.getConfluenceEmail()) && present(org.getConfluenceApiToken())) {
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

    @Transactional
    public void changeOrgTier(String orgId, String newTier, String performedBy) {
        Org org = orgRepo.findById(orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Org not found"));
        String oldTier = org.getTier();
        org.setTier(newTier);
        org.setUpdatedAt(Instant.now());
        orgRepo.save(org);
        paymentAuditService.record("ORG", orgId, "PLAN_UPGRADED", oldTier, newTier, performedBy, null);
    }
}
