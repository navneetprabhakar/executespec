package com.navneet.executespec.codeintel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase-1 deterministic <b>tiered</b> codemap selection for planner / agent injection
 * (RL-2026-06-003, flag {@code codeintel.tiered.enabled}).
 *
 * <p><b>Why.</b> Large repos produce large codemaps (matplotlib ≈ 58 KB). The injection budget
 * ({@code CODEINTEL_PER_REPO_CAP = 6000}) was applied as a HEAD truncation, which for a big repo
 * stops inside the flat {@code ## Files (listing)} section and cuts the {@code ## Functions}
 * per-file symbol map — the actual localization signal — 100%. We inject paths (noise) and drop
 * symbols (signal).
 *
 * <p><b>What this does.</b> Instead of head-truncating, parse the codemap and assemble within the
 * same budget:
 *
 * <ul>
 *   <li><b>T0 skeleton</b> (always): title + {@code ## Top-level layout} (+ {@code ## Routes} /
 *       {@code ## Models / entities} when present) — real structure, small.
 *   <li><b>T1 symbol digests</b> (selected): the {@code ## Functions} section split per-file; only
 *       the blocks whose path / symbols lexically overlap the spec are kept, ranked by relevance,
 *       until the budget is spent. The flat {@code ## Files (listing)} is dropped.
 * </ul>
 *
 * <p>Pure + deterministic (no LLM). <b>Fail-safe:</b> returns {@code null} to signal "fall back to
 * head-truncate" only when the codemap has no parseable structure; returns a T0-only block when no
 * symbol block matches the spec (still strictly better than the head file-listing). The lexical
 * scorer is a Phase-1 placeholder — Phase 2 swaps it for the pgvector semantic retriever (V223)
 * behind the same call.
 */
public final class TieredCodemapSelector {

  private TieredCodemapSelector() {}

  private static final int MIN_TOKEN_LEN = 3;
  private static final int PATH_MATCH_WEIGHT = 3;

  /** Common spec words that carry no localization signal — excluded from the relevance scorer. */
  private static final Set<String> STOP =
      Set.of(
          "the", "and", "for", "with", "that", "this", "are", "from", "into", "use", "using", "via",
          "add", "fix", "make", "when", "should", "support", "any", "all", "not", "but", "you",
          "your", "have", "has", "will", "can", "its", "new", "old", "raise", "return", "value",
          "values", "test", "tests", "issue", "bug", "error", "errors", "case", "cases", "code");

  /**
   * @param codemapMarkdown the full per-repo codemap (markdown emitted by {@code CodeMapGenerator})
   * @param specText the run's natural-language requirement (relevance signal); blank ⇒ {@code null}
   * @param budget max chars for the returned body
   * @return the tiered body within {@code budget}, or {@code null} to signal the caller should fall
   *     back to head-truncation (no parseable structure / blank spec)
   */
  public static String select(String codemapMarkdown, String specText, int budget) {
    if (codemapMarkdown == null || codemapMarkdown.isBlank() || budget <= 0) return null;
    if (specText == null || specText.isBlank()) return null; // no relevance signal → head-truncate

    String[] lines = codemapMarkdown.split("\n", -1);
    StringBuilder title = new StringBuilder();
    Map<String, StringBuilder> sections =
        new LinkedHashMap<>(); // section name -> full text (incl. header)
    String cur = null;
    for (String ln : lines) {
      if (ln.startsWith("## ")) {
        cur = ln.substring(3).trim();
        sections.computeIfAbsent(cur, k -> new StringBuilder()).append(ln).append("\n");
      } else if (cur == null) {
        title.append(ln).append("\n");
      } else {
        sections.get(cur).append(ln).append("\n");
      }
    }
    if (sections.isEmpty()) return null; // not our format → head-truncate

    // T1 candidate blocks from the Functions section
    List<Block> blocks = parseFunctionBlocks(sections.get("Functions"));
    Set<String> specTokens = tokenize(specText);
    for (Block b : blocks) b.score = score(b, specTokens);
    blocks.sort((x, y) -> Integer.compare(y.score, x.score));

    // assemble
    StringBuilder out = new StringBuilder();
    String t = title.toString().trim();
    if (!t.isEmpty()) out.append(t).append("\n\n");
    for (String s : List.of("Top-level layout", "Routes", "Models / entities")) {
      StringBuilder sec = sections.get(s);
      if (sec != null) appendCapped(out, sec.toString().trim(), budget);
    }

    int included = 0;
    StringBuilder t1 = new StringBuilder();
    for (Block b : blocks) {
      if (b.score <= 0) break; // only spec-relevant files
      int projected = out.length() + t1.length() + b.text.length() + 40;
      if (projected > budget) {
        if (included == 0) {
          // top block alone overflows — include a budget-fitted head of it so we never inject 0
          // symbols
          int room = budget - out.length() - 60;
          if (room > 200) t1.append(b.text, 0, Math.min(b.text.length(), room)).append("\n");
          included++;
        }
        break;
      }
      t1.append(b.text).append("\n");
      included++;
    }
    if (included > 0) {
      out.append("## Functions — files most relevant to this task (")
          .append(included)
          .append(" of ")
          .append(blocks.size())
          .append(")\n\n")
          .append(t1);
    }
    String result = out.toString().trim();
    if (result.isEmpty()) return null;
    if (result.length() > budget) {
      // hard budget guarantee — clamp at a line boundary in the back half if one exists
      int nl = result.lastIndexOf('\n', budget);
      result = result.substring(0, nl > budget / 2 ? nl : budget);
    }
    return result;
  }

  private static List<Block> parseFunctionBlocks(StringBuilder functionsSection) {
    List<Block> blocks = new ArrayList<>();
    if (functionsSection == null) return blocks;
    String[] lines = functionsSection.toString().split("\n", -1);
    String path = null;
    StringBuilder body = null;
    for (String ln : lines) {
      if (ln.startsWith("### ")) {
        if (path != null) blocks.add(new Block(path, body.toString()));
        path = ln.substring(4).trim().replace("`", "");
        body = new StringBuilder().append(ln).append("\n");
      } else if (body != null) {
        body.append(ln).append("\n");
      }
      // lines before the first ### (the section header + intro) are intentionally dropped
    }
    if (path != null) blocks.add(new Block(path, body.toString()));
    return blocks;
  }

  private static int score(Block b, Set<String> specTokens) {
    String pathLc = b.path.toLowerCase();
    String textLc = b.text.toLowerCase();
    int s = 0;
    for (String tok : specTokens) {
      if (pathLc.contains(tok)) s += PATH_MATCH_WEIGHT;
      else if (textLc.contains(tok)) s += 1;
    }
    return s;
  }

  private static Set<String> tokenize(String text) {
    Set<String> out = new java.util.HashSet<>();
    for (String raw : text.toLowerCase().split("[^a-z0-9]+")) {
      if (raw.length() >= MIN_TOKEN_LEN && !STOP.contains(raw)) out.add(raw);
    }
    return out;
  }

  private static void appendCapped(StringBuilder out, String section, int budget) {
    if (section.isEmpty()) return;
    if (out.length() + section.length() + 2 > budget) return; // skip T0 section that won't fit
    out.append(section).append("\n\n");
  }

  private static final class Block {
    final String path;
    final String text;
    int score;

    Block(String path, String text) {
      this.path = path;
      this.text = text;
    }
  }
}
