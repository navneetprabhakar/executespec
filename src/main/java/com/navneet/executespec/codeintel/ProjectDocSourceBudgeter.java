package com.navneet.executespec.codeintel;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Fair, deterministic character budgeting for multi-repository document evidence. */
final class ProjectDocSourceBudgeter {

  private static final String OMITTED_MARKER =
      "[section evidence truncated at a complete line; omitted content may contain additional "
          + "facts]\n";

  private ProjectDocSourceBudgeter() {}

  record Section(String heading, String content) {}

  static String budget(List<Section> sections, int characterLimit) {
    if (sections == null || sections.isEmpty()) return "";
    long structuralChars =
        sections.stream()
            .mapToLong(
                section ->
                    safeHeading(section.heading()).length() + 1 + OMITTED_MARKER.length() + 2)
            .sum();
    if (structuralChars > characterLimit) {
      throw new IllegalStateException("Too many repository evidence sections to budget safely");
    }

    int[] allocations = allocate(sections, characterLimit - Math.toIntExact(structuralChars));
    StringBuilder result = new StringBuilder(characterLimit);
    for (int i = 0; i < sections.size(); i++) {
      Section section = sections.get(i);
      String content = content(section);
      result.append(safeHeading(section.heading())).append('\n');
      result.append(
          allocations[i] >= content.length()
              ? content
              : truncateAtLineBoundary(content, allocations[i]));
      if (result.isEmpty() || result.charAt(result.length() - 1) != '\n') result.append('\n');
      if (allocations[i] < content.length()) result.append(OMITTED_MARKER);
      result.append('\n');
    }
    if (result.length() > characterLimit) {
      throw new IllegalStateException("Repository evidence budget accounting exceeded its limit");
    }
    return result.toString();
  }

  private static int[] allocate(List<Section> sections, int available) {
    int[] allocations = new int[sections.size()];
    Set<Integer> active = new LinkedHashSet<>();
    for (int i = 0; i < sections.size(); i++) active.add(i);
    int remaining = available;
    while (!active.isEmpty() && remaining > 0) {
      int share = remaining / active.size();
      if (share == 0) {
        int granted = 0;
        for (Integer index : active) allocations[index] = granted++ < remaining ? 1 : 0;
        break;
      }
      boolean consumedShortSection = false;
      for (var iterator = active.iterator(); iterator.hasNext(); ) {
        int index = iterator.next();
        int length = content(sections.get(index)).length();
        if (length <= share) {
          allocations[index] = length;
          remaining -= length;
          iterator.remove();
          consumedShortSection = true;
        }
      }
      if (!consumedShortSection) {
        int index = 0;
        for (Integer activeIndex : active) {
          allocations[activeIndex] = share + (index++ < remaining % active.size() ? 1 : 0);
        }
        break;
      }
    }
    return allocations;
  }

  private static String content(Section section) {
    return section.content() == null ? "" : section.content();
  }

  private static String truncateAtLineBoundary(String content, int allowance) {
    if (allowance <= 0) return "";
    if (content.length() <= allowance) return content;
    int cut = content.lastIndexOf('\n', allowance);
    return cut >= 0 ? content.substring(0, cut + 1) : "";
  }

  private static String safeHeading(String heading) {
    if (heading == null || heading.isBlank()) return "# Evidence";
    String singleLine = heading.replace('\n', ' ').replace('\r', ' ').trim();
    return singleLine.length() <= 512 ? singleLine : singleLine.substring(0, 512);
  }
}
