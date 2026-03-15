package com.navneet.executespec.billing.controller;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.service.TierPermissionService;
import com.navneet.executespec.billing.entity.AccountingEntry;
import com.navneet.executespec.billing.service.AccountingService;
import com.navneet.executespec.billing.service.AccountingService.GstSummary;
import com.navneet.executespec.billing.service.AccountingService.RevenueMetrics;
import com.navneet.executespec.billing.service.AccountingService.TrialBalanceEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/accounting")
@RequiredArgsConstructor
public class AccountingController {

    private final AccountingService accountingService;
    private final TierPermissionService permissionService;

    @GetMapping("/revenue")
    public RevenueMetrics getRevenue(
            @AuthenticationPrincipal AppUserDetails principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireAccountingAccess(principal);
        return accountingService.getRevenueMetrics(from, to);
    }

    @GetMapping("/gst-summary")
    public GstSummary getGstSummary(
            @AuthenticationPrincipal AppUserDetails principal,
            @RequestParam String fy,
            @RequestParam int quarter) {
        requireAccountingAccess(principal);
        return accountingService.getGstSummary(fy, quarter);
    }

    @GetMapping("/gstr1-export")
    public ResponseEntity<byte[]> exportGstr1(
            @AuthenticationPrincipal AppUserDetails principal,
            @RequestParam String fy,
            @RequestParam int quarter) {
        requireGstrExport(principal);
        byte[] csv = accountingService.exportGstr1(fy, quarter);
        String filename = "GSTR1_" + fy + "_Q" + quarter + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv);
    }

    @GetMapping("/trial-balance")
    public List<TrialBalanceEntry> getTrialBalance(
            @AuthenticationPrincipal AppUserDetails principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        requireAccountingAccess(principal);
        return accountingService.getTrialBalance(from, to);
    }

    @GetMapping("/entries")
    public Page<AccountingEntry> getEntries(
            @AuthenticationPrincipal AppUserDetails principal,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Pageable pageable) {
        requireAccountingAccess(principal);
        return accountingService.getEntries(from, to, pageable);
    }

    // ── Access checks ────────────────────────────────────────────────────────

    private void requireAccountingAccess(AppUserDetails principal) {
        var perms = permissionService.getPermissions(principal.getUser().getId());
        if (!perms.canViewAccounting()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Accounting access required");
        }
    }

    private void requireGstrExport(AppUserDetails principal) {
        var perms = permissionService.getPermissions(principal.getUser().getId());
        if (!perms.canExportGstr()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "GSTR export access required");
        }
    }
}
