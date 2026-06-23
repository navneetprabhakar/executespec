package com.navneet.executespec.agentsession;

import java.util.Map;

final class AgentActionArguments {

  private AgentActionArguments() {}

  static String stringArg(Map<String, Object> arguments, String key) {
    if (arguments == null || !arguments.containsKey(key) || arguments.get(key) == null) {
      return null;
    }
    return String.valueOf(arguments.get(key));
  }
}
