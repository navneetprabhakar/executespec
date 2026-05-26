package com.navneet.executespec.auth.dto;

/**
 * Response to PAT creation. {@code token} is the plaintext credential — returned exactly once,
 * here, and never retrievable again. {@code meta} is the persisted, secret-free record.
 */
public record CreatePatResponse(String token, PatDto meta) {}
