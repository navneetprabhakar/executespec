package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class AgentActionArgumentsTest {

  @Test
  void stringArgReturnsNullForMissingNullMapOrNullValue() {
    assertThat(AgentActionArguments.stringArg(null, "projectId")).isNull();
    assertThat(AgentActionArguments.stringArg(Map.of(), "projectId")).isNull();
    assertThat(AgentActionArguments.stringArg(new java.util.HashMap<>(), "projectId")).isNull();

    var args = new java.util.HashMap<String, Object>();
    args.put("projectId", null);
    assertThat(AgentActionArguments.stringArg(args, "projectId")).isNull();
  }

  @Test
  void stringArgPreservesStringValueAndStringifiesNonStringValues() {
    assertThat(AgentActionArguments.stringArg(Map.of("spec", " fix this "), "spec"))
        .isEqualTo(" fix this ");
    assertThat(AgentActionArguments.stringArg(Map.of("count", 3), "count")).isEqualTo("3");
  }
}
