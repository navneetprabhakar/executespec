package com.navneet.executespec.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.ExecutespecApplication;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.scheduling.annotation.EnableScheduling;

class SchedulingProfileConfigTest {

  @Test
  void schedulingIsDisabledWhenLocalProfileIsActive() {
    assertThat(
            AnnotatedElementUtils.findMergedAnnotation(
                ExecutespecApplication.class, EnableScheduling.class))
        .isNull();

    Profile profile =
        AnnotatedElementUtils.findMergedAnnotation(SchedulingProfileConfig.class, Profile.class);
    assertThat(profile).isNotNull();
    assertThat(profile.value()).containsExactly("!local");
    assertThat(
            AnnotatedElementUtils.findMergedAnnotation(
                SchedulingProfileConfig.class, EnableScheduling.class))
        .isNotNull();
  }
}
