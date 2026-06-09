package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * W3.1 (vix-leverage P2) — pins the Java structural outline (package/imports/type/methods/fields +
 * line ranges via JavaParser), the flag gate (FORBIDDEN when off; drives the ToolRegistry binding
 * gate via {@code enabled()}), the Java-only guard, and the syntax-error fail-safe.
 */
@DisplayName("ReadCodeOutlineTool (W3.1 — Java structural outline)")
class ReadCodeOutlineToolTest {

  private static final ObjectMapper M = new ObjectMapper();

  @TempDir Path ws;
  private FeatureFlagService flags;
  private ReadCodeOutlineTool tool;

  @BeforeEach
  void setUp() {
    flags = mock(FeatureFlagService.class);
    tool = new ReadCodeOutlineTool(flags);
  }

  private JsonNode args(String json) {
    try {
      return M.readTree(json);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private ExecutionContext ctx() {
    return new ExecutionContext("run-o", "T1", ws);
  }

  private void flagOn() {
    when(flags.isEnabled(ReadCodeOutlineTool.FLAG)).thenReturn(true);
  }

  @Test
  @DisplayName("outline() extracts package, imports, type, methods/fields with line ranges")
  void outlineExtracts() {
    String src =
        "package a.b;\n"
            + "import java.util.List;\n\n"
            + "public class Foo {\n"
            + "  private int x;\n"
            + "  public Foo() {}\n"
            + "  public int bar(String s) { return 1; }\n"
            + "}\n";
    String o = ReadCodeOutlineTool.outline("Foo.java", src);
    assertThat(o)
        .contains("package a.b")
        .contains("import")
        .contains("class Foo")
        .contains("field int x")
        .contains("bar")
        .contains("(L"); // line range markers present
  }

  @Test
  @DisplayName("enabled() reflects the flag (drives the ToolRegistry binding gate)")
  void enabledReflectsFlag() {
    when(flags.isEnabled(ReadCodeOutlineTool.FLAG)).thenReturn(false);
    assertThat(tool.enabled()).isFalse();
    when(flags.isEnabled(ReadCodeOutlineTool.FLAG)).thenReturn(true);
    assertThat(tool.enabled()).isTrue();
  }

  @Test
  @DisplayName("flag OFF → FORBIDDEN")
  void flagOff() {
    when(flags.isEnabled(ReadCodeOutlineTool.FLAG)).thenReturn(false);
    assertThat(tool.execute(args("{\"path\":\"X.java\"}"), ctx()))
        .isInstanceOf(ToolResult.Err.class);
  }

  @Test
  @DisplayName("non-.java path → INVALID_ARGS")
  void nonJava() {
    flagOn();
    assertThat(tool.execute(args("{\"path\":\"foo.py\"}"), ctx()))
        .isInstanceOf(ToolResult.Err.class);
  }

  @Test
  @DisplayName("valid .java file → Ok with the outline")
  void validFile() throws Exception {
    flagOn();
    Files.writeString(ws.resolve("Foo.java"), "package a;\npublic class Foo { void m() {} }\n");
    ToolResult r = tool.execute(args("{\"path\":\"Foo.java\"}"), ctx());
    assertThat(r).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) r).content()).contains("class Foo").contains("m");
  }

  @Test
  @DisplayName("syntax-error .java → fail-safe Ok pointing at readFile (never a hard error)")
  void syntaxError() throws Exception {
    flagOn();
    Files.writeString(ws.resolve("Bad.java"), "public class Bad { this is not java");
    ToolResult r = tool.execute(args("{\"path\":\"Bad.java\"}"), ctx());
    assertThat(r).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) r).content()).containsIgnoringCase("could not parse");
  }
}
