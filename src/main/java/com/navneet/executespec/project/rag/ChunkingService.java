package com.navneet.executespec.project.rag;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * Token-aware chunker. Splits text into ~500-token windows with 50-token overlap. Token estimation
 * uses the standard chars/4 heuristic (matches the rest of the codebase). Avoids splitting inside
 * fenced code blocks when feasible.
 */
@Component
public class ChunkingService {

  private static final int TARGET_TOKENS = 500;
  private static final int OVERLAP_TOKENS = 50;
  private static final int CHARS_PER_TOKEN = 4;

  public record Chunk(int index, String content, int tokenCount) {}

  public List<Chunk> chunk(String text) {
    if (text == null || text.isBlank()) return List.of();
    int targetChars = TARGET_TOKENS * CHARS_PER_TOKEN;
    int overlapChars = OVERLAP_TOKENS * CHARS_PER_TOKEN;
    List<Chunk> out = new ArrayList<>();
    int pos = 0;
    int idx = 0;
    int len = text.length();
    while (pos < len) {
      int end = Math.min(pos + targetChars, len);
      // Try to extend to closing fence if mid-block.
      int fenceCount = countFences(text, pos, end);
      if (fenceCount % 2 == 1) {
        int closeIdx = text.indexOf("```", end);
        if (closeIdx >= 0 && closeIdx - pos < targetChars + 800) {
          end = Math.min(closeIdx + 3, len);
        }
      }
      String slice = text.substring(pos, end);
      int tokenCount = Math.max(1, slice.length() / CHARS_PER_TOKEN);
      out.add(new Chunk(idx++, slice, tokenCount));
      if (end >= len) break;
      pos = Math.max(pos + 1, end - overlapChars);
    }
    return out;
  }

  private static int countFences(String text, int start, int end) {
    int c = 0;
    int i = start;
    while ((i = text.indexOf("```", i)) >= 0 && i < end) {
      c++;
      i += 3;
    }
    return c;
  }
}
