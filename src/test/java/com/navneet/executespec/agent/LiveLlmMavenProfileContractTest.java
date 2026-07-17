package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class LiveLlmMavenProfileContractTest {

  @Test
  void springBootRunPropagatesTheFailClosedLiveTestProperties() throws Exception {
    String pom = Files.readString(Path.of("pom.xml"));
    String springBootPlugin =
        pom.substring(
            pom.indexOf("<artifactId>spring-boot-maven-plugin</artifactId>"),
            pom.indexOf("<artifactId>maven-surefire-plugin</artifactId>"));

    assertThat(springBootPlugin)
        .contains(
            "<executespec.live-llm.enabled>${executespec.live-llm.enabled}</executespec.live-llm.enabled>",
            "<executespec.live-llm.test-mode>${executespec.live-llm.test-mode}</executespec.live-llm.test-mode>",
            "<executespec.live-llm.reservation-id>${executespec.live-llm.reservation-id}</executespec.live-llm.reservation-id>");
  }

  @Test
  void liveTestProfileEnablesTransportButStillRequiresAnExplicitReservation() throws Exception {
    String pom = Files.readString(Path.of("pom.xml"));
    int profileStart = pom.indexOf("<id>live-llm-test</id>");
    String profile = pom.substring(profileStart, pom.indexOf("</profile>", profileStart));

    assertThat(profile)
        .contains(
            "<executespec.live-llm.enabled>true</executespec.live-llm.enabled>",
            "<executespec.live-llm.test-mode>true</executespec.live-llm.test-mode>")
        .doesNotContain("<executespec.live-llm.reservation-id>");
  }
}
