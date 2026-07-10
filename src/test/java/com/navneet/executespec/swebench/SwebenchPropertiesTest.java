package com.navneet.executespec.swebench;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SwebenchPropertiesTest {

  @Test
  void keepsHistoricalHardCapDefaultForCompatibility() {
    assertThat(new SwebenchProperties().getHardCapUsd()).isEqualTo(8.0);
  }

  @Test
  void permitsAnExplicitLowerCampaignCap() {
    SwebenchProperties properties = new SwebenchProperties();
    properties.setHardCapUsd(0.25);

    assertThat(properties.getHardCapUsd()).isEqualTo(0.25);
  }
}
