package com.navneet.executespec.v3.referral;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * R-162 follow-up — JPA repository for {@link ReferralInvite}.
 *
 * <p>All lookups go through the lower-cased referee email for case-insensitive matching (matches
 * the V153 unique index).
 */
public interface ReferralInviteRepository extends JpaRepository<ReferralInvite, String> {

  @Query("SELECT r FROM ReferralInvite r WHERE LOWER(r.refereeEmail) = LOWER(:email)")
  Optional<ReferralInvite> findByRefereeEmailIgnoreCase(@Param("email") String email);

  @Query(
      "SELECT r FROM ReferralInvite r WHERE r.referrerUserId = :referrerId AND r.grantedAt IS NULL")
  List<ReferralInvite> findPendingByReferrer(@Param("referrerId") String referrerId);

  @Query(
      "SELECT r FROM ReferralInvite r WHERE r.acceptedByUserId = :userId AND r.grantedAt IS NULL")
  Optional<ReferralInvite> findUngrantedByAcceptedUser(@Param("userId") String userId);

  @Query(
      "SELECT r FROM ReferralInvite r WHERE r.referrerUserId = :referrerId AND"
          + " LOWER(r.refereeEmail) = LOWER(:email)")
  Optional<ReferralInvite> findByReferrerAndEmail(
      @Param("referrerId") String referrerId, @Param("email") String email);

  /**
   * R-162 magic-link landing — resolve a token to its invite row so the V3Auth page can render
   * inviter context.
   */
  Optional<ReferralInvite> findByToken(String token);
}
