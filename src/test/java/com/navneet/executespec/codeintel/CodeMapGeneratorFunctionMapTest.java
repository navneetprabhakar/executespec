package com.navneet.executespec.codeintel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor;
import com.navneet.executespec.repo.GitHubRepoService;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Pins the function-map extraction added 2026-05-10. Pre-fix the codemap was a header "Files: N"
 * line plus a top-level dir list — downstream agents knew shape but not what each file did. The new
 * {@link CodeMapGenerator#extractSymbols(String, String)} pulls function / method / class names per
 * file via regex (no AST) so the planner can see "user.service.ts exports getUser, listUsers"
 * without paying the LLM-read cost.
 *
 * <p>Tests use the static {@code extractSymbols} helper for the regex contracts directly, and a
 * mocked {@link GitHubRepoService} for the orchestration assertions.
 */
class CodeMapGeneratorFunctionMapTest {

  // Pure regex contracts per language

  @Test
  void typescript_extracts_function_const_arrow_class_method() {
    String src =
        ""
            + "import { Foo } from './foo';\n"
            + "\n"
            + "export class UserService {\n"
            + "  getUser(id: string): Promise<User> { return this.repo.find(id); }\n"
            + "  async listUsers(): Promise<User[]> {\n"
            + "    return [];\n"
            + "  }\n"
            + "}\n"
            + "\n"
            + "export const createUser = async (data: NewUser): Promise<User> => {\n"
            + "  return userRepo.save(data);\n"
            + "};\n"
            + "\n"
            + "export function deleteUser(id: string): void {\n"
            + "  userRepo.remove(id);\n"
            + "}\n";

    List<String> syms = CodeMapGenerator.extractSymbols("src/services/user.service.ts", src);

    assertTrue(syms.contains("class UserService"), "should detect class declaration");
    assertTrue(syms.contains("getUser"), "should detect class method");
    assertTrue(syms.contains("listUsers"), "should detect async class method");
    assertTrue(syms.contains("createUser"), "should detect const-arrow export");
    assertTrue(syms.contains("deleteUser"), "should detect named function export");
    assertFalse(syms.contains("if"), "regex should not surface JS keywords as functions");
    assertFalse(syms.contains("return"), "regex should not surface return as a function");
  }

  @Test
  void java_extracts_class_record_interface_method() {
    String src =
        ""
            + "package com.example;\n"
            + "\n"
            + "public class UserService {\n"
            + "  private final UserRepository repo;\n"
            + "\n"
            + "  public User getUser(String id) {\n"
            + "    return repo.findById(id);\n"
            + "  }\n"
            + "\n"
            + "  public List<User> listUsers() {\n"
            + "    return repo.findAll();\n"
            + "  }\n"
            + "\n"
            + "  protected void deleteUser(String id) throws NotFoundException {\n"
            + "    repo.delete(id);\n"
            + "  }\n"
            + "}\n"
            + "\n"
            + "public record UserDto(String id, String email) {}\n"
            + "\n"
            + "public interface UserGateway { User load(String id); }\n";

    List<String> syms = CodeMapGenerator.extractSymbols("src/main/java/UserService.java", src);

    assertTrue(syms.contains("class UserService"));
    // PR-N1 (2026-05-14) — AST extractor distinguishes record / interface / class. Pre-PR-N1
    // the regex lumped these all as "class X"; the AST output is more precise. Both styles
    // still convey the type information so downstream agents (planner, chat) read either.
    assertTrue(
        syms.contains("record UserDto") || syms.contains("class UserDto"),
        "AST emits 'record UserDto'; regex fallback emits 'class UserDto'");
    assertTrue(
        syms.contains("interface UserGateway") || syms.contains("class UserGateway"),
        "AST emits 'interface UserGateway'; regex fallback emits 'class UserGateway'");
    assertTrue(syms.contains("getUser"));
    assertTrue(syms.contains("listUsers"));
    assertTrue(syms.contains("deleteUser"), "should pick up methods with throws clauses");
  }

  @Test
  void python_extracts_class_def_async_def() {
    String src =
        ""
            + "from typing import List\n"
            + "\n"
            + "class UserService:\n"
            + "    def __init__(self, repo):\n"
            + "        self.repo = repo\n"
            + "\n"
            + "    def get_user(self, user_id: str):\n"
            + "        return self.repo.find(user_id)\n"
            + "\n"
            + "    async def list_users(self) -> List:\n"
            + "        return await self.repo.all()\n"
            + "\n"
            + "def helper_outside():\n"
            + "    pass\n";

    List<String> syms = CodeMapGenerator.extractSymbols("src/services/user.py", src);

    assertTrue(syms.contains("class UserService"));
    assertTrue(syms.contains("def __init__"));
    assertTrue(syms.contains("def get_user"));
    assertTrue(syms.contains("def list_users"), "async def captured");
    assertTrue(syms.contains("def helper_outside"));
  }

  @Test
  void go_extracts_top_level_func_and_methods() {
    String src =
        ""
            + "package users\n"
            + "\n"
            + "type Service struct{ repo Repo }\n"
            + "\n"
            + "func NewService(r Repo) *Service { return &Service{r} }\n"
            + "\n"
            + "func (s *Service) GetUser(id string) (*User, error) {\n"
            + "  return s.repo.Find(id)\n"
            + "}\n"
            + "\n"
            + "func (s *Service) ListUsers() ([]*User, error) {\n"
            + "  return s.repo.All()\n"
            + "}\n";

    List<String> syms = CodeMapGenerator.extractSymbols("internal/users/service.go", src);

    assertTrue(syms.contains("func NewService"));
    assertTrue(syms.contains("func GetUser"), "method receivers should still surface the name");
    assertTrue(syms.contains("func ListUsers"));
  }

  @Test
  void rust_extracts_pub_async_fn() {
    String src =
        ""
            + "pub mod users {\n"
            + "  pub async fn get_user(id: &str) -> Result<User> {\n"
            + "    repo.find(id).await\n"
            + "  }\n"
            + "  fn private_helper(x: i32) -> i32 { x + 1 }\n"
            + "  pub fn list_users<T>() -> Vec<T> { vec![] }\n"
            + "}\n";

    List<String> syms = CodeMapGenerator.extractSymbols("crates/users/src/lib.rs", src);

    assertTrue(syms.contains("fn get_user"));
    assertTrue(syms.contains("fn private_helper"));
    assertTrue(syms.contains("fn list_users"));
  }

  @Test
  void unsupported_extension_returns_empty() {
    List<String> syms = CodeMapGenerator.extractSymbols("config/app.yml", "key: value\n");
    assertEquals(0, syms.size(), "yaml is not a supported source language");
  }

  @Test
  void empty_or_blank_content_returns_empty() {
    assertTrue(CodeMapGenerator.extractSymbols("a/b/c.ts", "").isEmpty());
    assertTrue(CodeMapGenerator.extractSymbols("a/b/c.ts", "   \n  \n").isEmpty());
  }

  @Test
  void deduplicates_repeated_method_signatures() {
    // Overloaded Java methods should appear once — the listing is "what's defined", not call count.
    String src =
        ""
            + "public class Foo {\n"
            + "  public void bar() {}\n"
            + "  public void bar(String s) {}\n"
            + "  public void bar(int n, String s) {}\n"
            + "}\n";
    List<String> syms = CodeMapGenerator.extractSymbols("Foo.java", src);
    long barCount = syms.stream().filter("bar"::equals).count();
    assertEquals(1, barCount, "overloaded methods dedupe to one entry");
  }

  // End-to-end orchestration via mocked GitHubRepoService

  @Test
  void scanFunctions_skips_test_files_and_node_modules() {
    GitHubRepoService gh = mock(GitHubRepoService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<FilePurposeSummaryService> nullSummaryProvider = mock(ObjectProvider.class);
    CodeMapGenerator gen =
        new CodeMapGenerator(
            gh,
            new SpringHttpArchitectureEvidenceRenderer(gh, new SpringHttpSurfaceExtractor()),
            nullSummaryProvider);

    List<String> files =
        List.of(
            "src/services/user.service.ts",
            "src/services/__tests__/user.service.test.ts",
            "node_modules/lodash/index.js",
            "src/components/Button.tsx",
            "dist/bundle.js");

    lenient()
        .when(gh.readRepoFile(any(), eq("src/services/user.service.ts"), any(), any()))
        .thenReturn(
            GitHubRepoService.RepoFileResult.success(
                "export const getUser = async (): Promise<User> => null;"));
    lenient()
        .when(gh.readRepoFile(any(), eq("src/components/Button.tsx"), any(), any()))
        .thenReturn(
            GitHubRepoService.RepoFileResult.success("export function Button() { return null; }"));
    // Test/vendor/dist files: even if accidentally read, we shouldn't hit the wire — but stub
    // them to throw so a regression that scans them blows up obviously.
    lenient()
        .when(
            gh.readRepoFile(any(), eq("src/services/__tests__/user.service.test.ts"), any(), any()))
        .thenThrow(new RuntimeException("test file should be skipped"));
    lenient()
        .when(gh.readRepoFile(any(), eq("node_modules/lodash/index.js"), any(), any()))
        .thenThrow(new RuntimeException("node_modules should be skipped"));
    lenient()
        .when(gh.readRepoFile(any(), eq("dist/bundle.js"), any(), any()))
        .thenThrow(new RuntimeException("dist should be skipped"));

    Map<String, List<String>> out = gen.scanFunctions("owner/repo", "main", files, "pat");

    assertTrue(out.containsKey("src/services/user.service.ts"), "should scan service file");
    assertTrue(out.containsKey("src/components/Button.tsx"), "should scan component file");
    assertFalse(
        out.containsKey("src/services/__tests__/user.service.test.ts"),
        "should not scan test files");
    assertFalse(out.containsKey("node_modules/lodash/index.js"), "should not scan vendor");
    assertFalse(out.containsKey("dist/bundle.js"), "should not scan dist artefacts");
  }

  @Test
  void scanFunctions_caps_files_at_max() {
    GitHubRepoService gh = mock(GitHubRepoService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<FilePurposeSummaryService> nullSummaryProvider = mock(ObjectProvider.class);
    CodeMapGenerator gen =
        new CodeMapGenerator(
            gh,
            new SpringHttpArchitectureEvidenceRenderer(gh, new SpringHttpSurfaceExtractor()),
            nullSummaryProvider);

    // 60 service files — extractor should cap at MAX_FUNCTION_FILES (40).
    List<String> files =
        java.util.stream.IntStream.range(0, 60)
            .mapToObj(i -> "src/services/svc" + i + ".ts")
            .toList();
    lenient()
        .when(gh.readRepoFile(any(), any(), any(), any()))
        .thenReturn(GitHubRepoService.RepoFileResult.success("export function fn() { return 1; }"));

    Map<String, List<String>> out = gen.scanFunctions("owner/repo", "main", files, "pat");
    assertEquals(CodeMapGenerator.MAX_FUNCTION_FILES, out.size());
  }

  @Test
  void scanFunctions_caps_functions_per_file() {
    GitHubRepoService gh = mock(GitHubRepoService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<FilePurposeSummaryService> nullSummaryProvider = mock(ObjectProvider.class);
    CodeMapGenerator gen =
        new CodeMapGenerator(
            gh,
            new SpringHttpArchitectureEvidenceRenderer(gh, new SpringHttpSurfaceExtractor()),
            nullSummaryProvider);

    StringBuilder content = new StringBuilder();
    for (int i = 0; i < 50; i++) {
      content
          .append("export function fn")
          .append(i)
          .append("() { return ")
          .append(i)
          .append("; }\n");
    }

    lenient()
        .when(gh.readRepoFile(any(), eq("src/many.ts"), any(), any()))
        .thenReturn(GitHubRepoService.RepoFileResult.success(content.toString()));

    Map<String, List<String>> out =
        gen.scanFunctions("owner/repo", "main", List.of("src/many.ts"), "pat");

    List<String> fns = out.get("src/many.ts");
    assertEquals(CodeMapGenerator.MAX_FUNCTIONS_PER_FILE, fns.size());
    assertEquals("fn0", fns.get(0), "preserves source order");
  }

  @Test
  void generate_includes_functions_section_when_any_match() {
    GitHubRepoService gh = mock(GitHubRepoService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<FilePurposeSummaryService> nullSummaryProvider = mock(ObjectProvider.class);
    CodeMapGenerator gen =
        new CodeMapGenerator(
            gh,
            new SpringHttpArchitectureEvidenceRenderer(gh, new SpringHttpSurfaceExtractor()),
            nullSummaryProvider);

    when(gh.listRepoFilesResult(any(), any(), any()))
        .thenReturn(
            GitHubRepoService.RepoFileListResult.success(
                List.of("src/services/user.service.ts", "package.json", "README.md")));
    lenient()
        .when(gh.readRepoFile(any(), eq("src/services/user.service.ts"), any(), any()))
        .thenReturn(
            GitHubRepoService.RepoFileResult.success(
                "export const getUser = (): void => null;\nexport function listUsers() {}"));

    String md = gen.generate("owner/repo", "main", "pat");

    assertTrue(md.contains("## Functions"), "function section emitted");
    assertTrue(md.contains("src/services/user.service.ts"), "file path heading present");
    assertTrue(md.contains("getUser"), "extracted symbol present");
    assertTrue(md.contains("listUsers"), "extracted symbol present");
  }

  @Test
  void generatePinsExactSpringRouteAndConfigurationEvidenceToSourceRevision() {
    GitHubRepoService gh = mock(GitHubRepoService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<FilePurposeSummaryService> nullSummaryProvider = mock(ObjectProvider.class);
    CodeMapGenerator gen =
        new CodeMapGenerator(
            gh,
            new SpringHttpArchitectureEvidenceRenderer(gh, new SpringHttpSurfaceExtractor()),
            nullSummaryProvider);
    String controller = "src/main/java/example/SimulationController.java";
    String config = "src/main/resources/application.properties";
    when(gh.listRepoFilesResult(any(), eq("deadbeef"), any()))
        .thenReturn(GitHubRepoService.RepoFileListResult.success(List.of(controller, config)));
    lenient()
        .when(gh.readRepoFile(any(), eq(controller), eq("deadbeef"), any()))
        .thenReturn(
            GitHubRepoService.RepoFileResult.success(
                """
                @RestController
                class SimulationController {
                  @PostMapping("/casino-game")
                  Object simulate() { return null; }
                }
                """));
    lenient()
        .when(gh.readRepoFile(any(), eq(config), eq("deadbeef"), any()))
        .thenReturn(
            GitHubRepoService.RepoFileResult.success("server.servlet.context-path=/simulation\n"));

    String markdown = gen.generate("owner/repo", "deadbeef", "pat");

    assertTrue(markdown.contains("Source revision: `deadbeef`"));
    assertTrue(markdown.contains("Status: `AVAILABLE`"));
    assertTrue(markdown.contains("`POST /simulation/casino-game`"));
    assertTrue(markdown.contains("`server.servlet.context-path=/simulation`"));
    assertTrue(markdown.contains("`src/main/resources/application.properties:1`"));
    assertTrue(markdown.contains("`src/main/java/example/SimulationController.java:3`"));
  }

  @Test
  void generateMarksSpringSurfaceUnavailableWhenAnyPinnedSourceReadFails() {
    GitHubRepoService gh = mock(GitHubRepoService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<FilePurposeSummaryService> nullSummaryProvider = mock(ObjectProvider.class);
    CodeMapGenerator gen =
        new CodeMapGenerator(
            gh,
            new SpringHttpArchitectureEvidenceRenderer(gh, new SpringHttpSurfaceExtractor()),
            nullSummaryProvider);
    String controller = "src/main/java/example/SimulationController.java";
    when(gh.listRepoFilesResult(any(), eq("deadbeef"), any()))
        .thenReturn(GitHubRepoService.RepoFileListResult.success(List.of(controller)));
    lenient()
        .when(gh.readRepoFile(any(), eq(controller), eq("deadbeef"), any()))
        .thenReturn(GitHubRepoService.RepoFileResult.failed("GITHUB_CONTENTS_HTTP_ERROR", 503));

    String markdown = gen.generate("owner/repo", "deadbeef", "pat");

    assertTrue(markdown.contains("Status: `UNAVAILABLE`"));
    assertTrue(markdown.contains("Reason: `GITHUB_CONTENTS_HTTP_ERROR`"));
    assertFalse(markdown.contains("POST /"));
  }

  @Test
  void generate_omits_functions_section_when_zero_matches() {
    // No interesting source files → no Functions section. The header + Top-level layout still
    // render so the generator's other contracts are unbroken.
    GitHubRepoService gh = mock(GitHubRepoService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<FilePurposeSummaryService> nullSummaryProvider = mock(ObjectProvider.class);
    CodeMapGenerator gen =
        new CodeMapGenerator(
            gh,
            new SpringHttpArchitectureEvidenceRenderer(gh, new SpringHttpSurfaceExtractor()),
            nullSummaryProvider);

    when(gh.listRepoFilesResult(any(), any(), any()))
        .thenReturn(
            GitHubRepoService.RepoFileListResult.success(
                List.of("README.md", "LICENSE", "package.json")));

    String md = gen.generate("owner/repo", "main", "pat");

    assertFalse(md.contains("## Functions"), "no Functions section when nothing extracted");
    assertTrue(md.contains("# owner/repo"));
    assertTrue(md.contains("**Files**: 3"));
  }

  @Test
  void generate_failsClosedWhenRepositoryInventoryIsUnavailable() {
    GitHubRepoService gh = mock(GitHubRepoService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<FilePurposeSummaryService> nullSummaryProvider = mock(ObjectProvider.class);
    CodeMapGenerator gen =
        new CodeMapGenerator(
            gh,
            new SpringHttpArchitectureEvidenceRenderer(gh, new SpringHttpSurfaceExtractor()),
            nullSummaryProvider);
    when(gh.listRepoFilesResult(any(), any(), any()))
        .thenThrow(new RuntimeException("upstream body must not become a valid empty codemap"));

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> gen.generate("owner/repo", "main", "pat"));

    assertEquals("Repository inventory unavailable for codemap generation", failure.getMessage());
  }

  // RR-955 — an UNSUCCESSFUL typed inventory (missing PAT / auth outage / rate limit / upstream
  // error) must fail closed, not become a valid "Files: 0" codemap the caller persists.
  @Test
  void generate_failsClosedWhenTypedInventoryUnsuccessful() {
    GitHubRepoService gh = mock(GitHubRepoService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<FilePurposeSummaryService> nullSummaryProvider = mock(ObjectProvider.class);
    CodeMapGenerator gen =
        new CodeMapGenerator(
            gh,
            new SpringHttpArchitectureEvidenceRenderer(gh, new SpringHttpSurfaceExtractor()),
            nullSummaryProvider);
    when(gh.listRepoFilesResult(any(), any(), any()))
        .thenReturn(GitHubRepoService.RepoFileListResult.failed("MISSING_GITHUB_PAT", null));

    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> gen.generate("owner/repo", "main", "pat"));

    assertTrue(
        failure.getMessage().contains("MISSING_GITHUB_PAT"),
        "failure names the typed inventory error code");
  }

  // RR-955 — a truncated tree is a strict subset and must be labelled PARTIAL in-band so downstream
  // never treats it as a complete repository inventory.
  @Test
  void generate_marksTruncatedInventoryAsPartial() {
    GitHubRepoService gh = mock(GitHubRepoService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<FilePurposeSummaryService> nullSummaryProvider = mock(ObjectProvider.class);
    CodeMapGenerator gen =
        new CodeMapGenerator(
            gh,
            new SpringHttpArchitectureEvidenceRenderer(gh, new SpringHttpSurfaceExtractor()),
            nullSummaryProvider);
    when(gh.listRepoFilesResult(any(), any(), any()))
        .thenReturn(
            GitHubRepoService.RepoFileListResult.success(
                List.of("README.md", "package.json"), /* truncated= */ true));

    String md = gen.generate("owner/repo", "main", "pat");

    assertTrue(md.contains("Inventory: PARTIAL"), "truncated tree carries a PARTIAL marker");
  }

  // RR-956 — a null / blank HEAD SHA leaves no immutable provenance to record; generateWithSha must
  // fail closed rather than scan an unpinned branch and persist content under a null SHA.
  @Test
  void generateWithSha_failsClosedWhenHeadShaUnresolvable() {
    GitHubRepoService gh = mock(GitHubRepoService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<FilePurposeSummaryService> nullSummaryProvider = mock(ObjectProvider.class);
    CodeMapGenerator gen =
        new CodeMapGenerator(
            gh,
            new SpringHttpArchitectureEvidenceRenderer(gh, new SpringHttpSurfaceExtractor()),
            nullSummaryProvider);
    when(gh.getBranchHeadSha(any(), any(), any())).thenReturn(null);

    assertThrows(
        IllegalStateException.class, () -> gen.generateWithSha("owner/repo", "main", "pat"));
  }

  // RR-956 — once the HEAD SHA is resolved, the tree + file reads must be pinned to that immutable
  // SHA (passed as the ref), not the mutable branch name, so a mid-generation push cannot be
  // mislabelled under this SHA.
  @Test
  void generateWithSha_pinsInventoryReadToResolvedSha() {
    GitHubRepoService gh = mock(GitHubRepoService.class);
    @SuppressWarnings("unchecked")
    ObjectProvider<FilePurposeSummaryService> nullSummaryProvider = mock(ObjectProvider.class);
    CodeMapGenerator gen =
        new CodeMapGenerator(
            gh,
            new SpringHttpArchitectureEvidenceRenderer(gh, new SpringHttpSurfaceExtractor()),
            nullSummaryProvider);
    when(gh.getBranchHeadSha(any(), any(), any())).thenReturn("abc123sha");
    when(gh.listRepoFilesResult(any(), eq("abc123sha"), any()))
        .thenReturn(GitHubRepoService.RepoFileListResult.success(List.of("README.md")));

    CodeMapGenerator.Result result = gen.generateWithSha("owner/repo", "main", "pat");

    assertEquals("abc123sha", result.sha());
    // The inventory read used the resolved SHA as the ref, not "main". Verified implicitly: the
    // stub only matches eq("abc123sha"); a branch-name read would return null and NPE below.
    assertTrue(result.markdown().contains("# owner/repo"));
  }
}
