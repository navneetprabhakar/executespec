package com.navneet.executespec.billing.service;

import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.service.ActivityAuditService;
import com.navneet.executespec.auth.service.AdminMutationValues;
import com.navneet.executespec.billing.entity.TopupPackage;
import com.navneet.executespec.billing.repository.TopupPackageRepository;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * RR-011 (2026-07-21, P1 billing integrity) — validated, audited top-up-package administration.
 *
 * <p>{@code SuperAdminController} wrote purchasable top-up packages directly through the repository
 * with no domain validation (negative or zero run counts and prices were accepted) and emitted no
 * audit event — an unaudited financial-control change that could publish an invalid purchasable
 * configuration. Every mutation here rejects a non-positive count, a negative price and a blank
 * name with a {@code 400}, and appends the audit row (RR-009 — structured Jackson JSON) in the SAME
 * transaction as the write.
 */
@Service
@RequiredArgsConstructor
public class TopupPackageAdminService {

  private final TopupPackageRepository packageRepo;
  private final ActivityAuditService auditService;
  private final ObjectMapper objectMapper;

  @Transactional
  public TopupPackage create(String actorId, Map<String, Object> body) {
    String name = requireName(body.get("name"));
    int runCount = AdminMutationValues.requirePositiveInt(body.get("runCount"), "runCount");
    long amountInr = AdminMutationValues.requireNonNegativeLong(body.get("amountInr"), "amountInr");
    long amountUsd =
        body.containsKey("amountUsd") && body.get("amountUsd") != null
            ? AdminMutationValues.requireNonNegativeLong(body.get("amountUsd"), "amountUsd")
            : 0L;
    int sortOrder =
        body.containsKey("sortOrder") && body.get("sortOrder") != null
            ? AdminMutationValues.optionalNonNegativeInt(body.get("sortOrder"), "sortOrder")
            : 0;

    TopupPackage pkg =
        packageRepo.save(
            TopupPackage.builder()
                .name(name)
                .runCount(runCount)
                .amountInr(amountInr)
                .amountUsd(amountUsd)
                .active(true)
                .sortOrder(sortOrder)
                .build());

    audit(
        actorId,
        "TOPUP_PACKAGE_CREATED",
        pkg,
        Map.of(
            "name", name,
            "runCount", runCount,
            "amountInr", amountInr,
            "amountUsd", amountUsd,
            "active", true));
    return pkg;
  }

  @Transactional
  public TopupPackage update(String actorId, UUID id, Map<String, Object> body) {
    TopupPackage pkg = find(id);
    if (body.containsKey("name")) {
      pkg.setName(requireName(body.get("name")));
    }
    if (body.containsKey("runCount")) {
      pkg.setRunCount(AdminMutationValues.requirePositiveInt(body.get("runCount"), "runCount"));
    }
    if (body.containsKey("amountInr")) {
      pkg.setAmountInr(
          AdminMutationValues.requireNonNegativeLong(body.get("amountInr"), "amountInr"));
    }
    if (body.containsKey("amountUsd")) {
      pkg.setAmountUsd(
          AdminMutationValues.requireNonNegativeLong(body.get("amountUsd"), "amountUsd"));
    }
    if (body.containsKey("active")) {
      if (!(body.get("active") instanceof Boolean b)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "Field 'active' must be a boolean.");
      }
      pkg.setActive(b);
    }
    if (body.containsKey("sortOrder")) {
      pkg.setSortOrder(
          AdminMutationValues.optionalNonNegativeInt(body.get("sortOrder"), "sortOrder"));
    }
    packageRepo.save(pkg);

    audit(
        actorId,
        "TOPUP_PACKAGE_UPDATED",
        pkg,
        Map.of(
            "name", pkg.getName() == null ? "" : pkg.getName(),
            "runCount", pkg.getRunCount(),
            "amountInr", pkg.getAmountInr(),
            "amountUsd", pkg.getAmountUsd(),
            "active", pkg.isActive()));
    return pkg;
  }

  @Transactional
  public TopupPackage deactivate(String actorId, UUID id) {
    TopupPackage pkg = find(id);
    pkg.setActive(false);
    packageRepo.save(pkg);
    audit(actorId, "TOPUP_PACKAGE_DEACTIVATED", pkg, Map.of("active", false));
    return pkg;
  }

  private TopupPackage find(UUID id) {
    return packageRepo
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Package not found"));
  }

  private static String requireName(Object raw) {
    if (!(raw instanceof String s) || s.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Field 'name' is required and must be non-blank.");
    }
    return s.trim();
  }

  private void audit(
      String actorId, String eventType, TopupPackage pkg, Map<String, Object> value) {
    auditService.record(
        ActivityAudit.builder()
            .eventType(eventType)
            .entityType("BILLING")
            .entityId(pkg.getId() == null ? null : pkg.getId().toString())
            .actorId(actorId)
            .newValue(json(value))
            .build());
  }

  private String json(Map<String, Object> fields) {
    try {
      return objectMapper.writeValueAsString(fields);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return "{}";
    }
  }
}
