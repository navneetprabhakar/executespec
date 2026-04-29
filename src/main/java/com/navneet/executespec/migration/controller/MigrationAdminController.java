package com.navneet.executespec.migration.controller;

import com.navneet.executespec.migration.seeder.MigrationRecipeSeeder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * R-337 — admin endpoint for on-demand reseed of the Migration
 * Recipes catalog.  SUPER_ADMIN-only.
 */
@RestController
@RequestMapping("/api/admin/migrations")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class MigrationAdminController {

    private final MigrationRecipeSeeder seeder;

    @PostMapping("/reseed")
    public ResponseEntity<Map<String, Object>> reseed() {
        log.info("[R-337] manual reseed requested");
        int loaded = seeder.reseed();
        return ResponseEntity.ok(Map.of(
            "loaded", loaded,
            "status", "ok"
        ));
    }
}
