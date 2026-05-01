package com.navneet.executespec.readymade.controller;

import java.util.Map;

import com.navneet.executespec.readymade.seeder.ReadymadeSeeder;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * R-328 — admin endpoint for on-demand reseed of the Readymade Solutions catalog.
 *
 * <p>SUPER_ADMIN-only. Useful for: hot-loading a freshly-authored YAML file without restarting the
 * JVM, debugging a bad seed entry (catch + fix in dev), and the W7 community-contribution path
 * (after a moderator approves a submission, this endpoint is what promotes it into the live
 * catalog).
 */
@RestController
@RequestMapping("/api/admin/readymades")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class ReadymadeAdminController {

  private final ReadymadeSeeder seeder;

  @PostMapping("/reseed")
  public ResponseEntity<Map<String, Object>> reseed() {
    log.info("[R-328] manual reseed requested");
    int loaded = seeder.reseed();
    return ResponseEntity.ok(Map.of("loaded", loaded, "status", "ok"));
  }
}
