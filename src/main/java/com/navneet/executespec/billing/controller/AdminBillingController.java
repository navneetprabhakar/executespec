package com.navneet.executespec.billing.controller;

import com.navneet.executespec.billing.service.PlanSeederService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/billing")
@RequiredArgsConstructor
public class AdminBillingController {

    private final PlanSeederService planSeederService;

    @PostMapping("/seed-plans")
    public ResponseEntity<String> seedPlans() {
        planSeederService.seedAllPlans();
        return ResponseEntity.ok("Plans seeded successfully");
    }
}
