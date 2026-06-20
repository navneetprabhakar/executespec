package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AgentTextTest {

  @Test
  void firstNonBlankReturnsFirstNonBlankValueWithoutTrimmingIt() {
    assertThat(AgentText.firstNonBlank(null, "", "   ", " value ", "other")).isEqualTo(" value ");
    assertThat(AgentText.firstNonBlank(null, " ")).isNull();
  }

  @Test
  void blankToNullTrimsNonBlankValues() {
    assertThat(AgentText.blankToNull(null)).isNull();
    assertThat(AgentText.blankToNull("  ")).isNull();
    assertThat(AgentText.blankToNull("  value  ")).isEqualTo("value");
  }

  @Test
  void trimShortensLongValuesWithEllipsis() {
    assertThat(AgentText.trim(null, 8)).isNull();
    assertThat(AgentText.trim("short", 8)).isEqualTo("short");
    assertThat(AgentText.trim("123456789", 6)).isEqualTo("12345\u2026");
  }

  @Test
  void leafReturnsLastPathSegment() {
    assertThat(AgentText.leaf(null)).isNull();
    assertThat(AgentText.leaf("")).isNull();
    assertThat(AgentText.leaf("/tmp/project/")).isEqualTo("project");
    assertThat(AgentText.leaf("project")).isEqualTo("project");
  }
}
