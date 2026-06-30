package com.navneet.executespec.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

class OrgServiceSeatPolicyTest {

  @Test
  void teamSeatAdjustmentRequiresTwoSeatOrg() throws Exception {
    Field minSeats = OrgService.class.getDeclaredField("TEAM_MIN_SEATS");
    minSeats.setAccessible(true);

    assertThat(minSeats.getInt(null)).isEqualTo(2);
  }
}
