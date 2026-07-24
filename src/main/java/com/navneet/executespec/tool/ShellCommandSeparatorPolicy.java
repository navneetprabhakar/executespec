package com.navneet.executespec.tool;

/** Small lexical rules used while splitting sandboxed shell command segments. */
final class ShellCommandSeparatorPolicy {

  private ShellCommandSeparatorPolicy() {}

  /** POSIX {@code 2>&1} is redirection, not a background-command separator. */
  static boolean isFileDescriptorDuplication(char[] chars, int index, StringBuilder current) {
    return chars[index] == '&'
        && current.length() > 0
        && current.charAt(current.length() - 1) == '>'
        && index + 1 < chars.length
        && Character.isDigit(chars[index + 1]);
  }
}
