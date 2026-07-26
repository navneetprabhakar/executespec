package com.navneet.executespec.tool;

final class ShellExecutableFamily {

  private ShellExecutableFamily() {}

  static String allowlistName(String commandName) {
    if (commandName != null && commandName.matches("python3\\.\\d+")) return "python3";
    if (commandName != null && commandName.matches("pip3\\.\\d+")) return "pip3";
    return commandName;
  }
}
