package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import com.navneet.executespec.auth.dto.CreatePatRequest;
import com.navneet.executespec.auth.entity.PersonalAccessToken;
import com.navneet.executespec.auth.repository.PersonalAccessTokenRepository;

import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pins {@link PersonalAccessTokenService} semantics:
 *
 * <ul>
 *   <li>Only the SHA-256 hash is stored — never the plaintext; the plaintext is returned exactly
 *       once and is reconstructable only by the holder.
 *   <li>Resolution rejects unknown, expired, and non-prefixed inputs, and stamps {@code lastUsedAt}
 *       throttled so the auth hot path isn't a write path.
 *   <li>Revoke is idempotent and tenant-scoped.
 * </ul>
 *
 * <p>Pure Mockito; no Spring context.
 */
class PersonalAccessTokenServiceTest {

  private PersonalAccessTokenRepository repo;
  private PersonalAccessTokenService svc;

  @BeforeEach
  void setUp() {
    repo = mock(PersonalAccessTokenRepository.class);
    svc = new PersonalAccessTokenService(repo);
  }

  @Test
  void create_storesHashNotPlaintext_andReturnsPlaintextOnce() {
    ArgumentCaptor<PersonalAccessToken> saved = ArgumentCaptor.forClass(PersonalAccessToken.class);

    PersonalAccessTokenService.CreatedToken created =
        svc.create("user-1", new CreatePatRequest("laptop CLI", null));

    verify(repo).save(saved.capture());
    PersonalAccessToken row = saved.getValue();

    assertThat(created.plaintext()).startsWith("es_pat_");
    // The stored row holds the digest, never the credential itself.
    assertThat(row.getTokenHash()).isEqualTo(DigestUtils.sha256Hex(created.plaintext()));
    assertThat(row.getTokenHash()).isNotEqualTo(created.plaintext());
    assertThat(row.getTokenPrefix()).startsWith("es_pat_").hasSize(14);
    assertThat(created.plaintext()).startsWith(row.getTokenPrefix());
    assertThat(row.getUserId()).isEqualTo("user-1");
    assertThat(row.getScopes()).isEqualTo("full");
    assertThat(row.getExpiresAt()).isNull();
  }

  @Test
  void create_setsExpiryWhenRequested() {
    PersonalAccessTokenService.CreatedToken created =
        svc.create("user-1", new CreatePatRequest("ci", 30));
    assertThat(created.token().getExpiresAt())
        .isAfter(Instant.now())
        .isBefore(Instant.now().plus(31, ChronoUnit.DAYS));
  }

  @Test
  void create_rejectsBlankNameAndNonPositiveExpiry() {
    assertThatThrownBy(() -> svc.create("user-1", new CreatePatRequest("  ", null)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> svc.create("user-1", new CreatePatRequest("ok", 0)))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void resolve_returnsNullForNullMissingPrefix_withoutTouchingDb() {
    assertThat(svc.resolveUserId(null)).isNull();
    assertThat(svc.resolveUserId("Bearer-jwt-not-a-pat")).isNull();
    verify(repo, never()).findByTokenHashAndRevokedAtIsNull(any());
  }

  @Test
  void resolve_returnsUserId_andStampsLastUsedWhenStale() {
    String plaintext = "es_pat_abcdefg_sometokenvalue";
    PersonalAccessToken row =
        PersonalAccessToken.builder()
            .id("t1")
            .userId("user-9")
            .tokenHash(DigestUtils.sha256Hex(plaintext))
            .tokenPrefix("es_pat_abcdefg")
            .lastUsedAt(null)
            .build();
    when(repo.findByTokenHashAndRevokedAtIsNull(DigestUtils.sha256Hex(plaintext)))
        .thenReturn(Optional.of(row));

    assertThat(svc.resolveUserId(plaintext)).isEqualTo("user-9");
    verify(repo, times(1)).save(row); // stamped because lastUsedAt was null
    assertThat(row.getLastUsedAt()).isNotNull();
  }

  @Test
  void resolve_isThrottled_whenRecentlyUsed() {
    String plaintext = "es_pat_recentlyusedtoken";
    PersonalAccessToken row =
        PersonalAccessToken.builder()
            .id("t2")
            .userId("user-9")
            .tokenHash(DigestUtils.sha256Hex(plaintext))
            .lastUsedAt(Instant.now().minusSeconds(10))
            .build();
    when(repo.findByTokenHashAndRevokedAtIsNull(DigestUtils.sha256Hex(plaintext)))
        .thenReturn(Optional.of(row));

    assertThat(svc.resolveUserId(plaintext)).isEqualTo("user-9");
    verify(repo, never()).save(any()); // throttled — no write within the 5-min window
  }

  @Test
  void resolve_returnsNullForUnknownAndExpired() {
    String unknown = "es_pat_unknowntoken";
    when(repo.findByTokenHashAndRevokedAtIsNull(DigestUtils.sha256Hex(unknown)))
        .thenReturn(Optional.empty());
    assertThat(svc.resolveUserId(unknown)).isNull();

    String expired = "es_pat_expiredtoken";
    PersonalAccessToken row =
        PersonalAccessToken.builder()
            .id("t3")
            .userId("user-9")
            .tokenHash(DigestUtils.sha256Hex(expired))
            .expiresAt(Instant.now().minusSeconds(60))
            .build();
    when(repo.findByTokenHashAndRevokedAtIsNull(DigestUtils.sha256Hex(expired)))
        .thenReturn(Optional.of(row));
    assertThat(svc.resolveUserId(expired)).isNull();
  }

  @Test
  void revoke_setsRevokedAt_isIdempotent_andTenantScoped() {
    PersonalAccessToken row =
        PersonalAccessToken.builder().id("t1").userId("user-1").revokedAt(null).build();
    when(repo.findByIdAndUserId("t1", "user-1")).thenReturn(Optional.of(row));

    svc.revoke("user-1", "t1");
    assertThat(row.getRevokedAt()).isNotNull();
    verify(repo, times(1)).save(row);

    // Second revoke is a no-op (no further save).
    svc.revoke("user-1", "t1");
    verify(repo, times(1)).save(row);

    when(repo.findByIdAndUserId("nope", "user-1")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> svc.revoke("user-1", "nope"))
        .isInstanceOf(ResponseStatusException.class);
  }
}
