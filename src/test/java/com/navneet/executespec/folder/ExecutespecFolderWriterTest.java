package com.navneet.executespec.folder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.repo.GitHubRepoService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ExecutespecFolderWriter} (E1.4 chokepoint).
 *
 * <p>Uses a stubbed {@link HttpClient} that returns canned responses keyed by URL substring — lets
 * us drive the full PR + DIRECT_PUSH + read + drift flows end-to-end against the real Contents/Refs
 * API client code without touching GitHub. {@link GitHubRepoService} is mocked so we don't need to
 * wire its HTTP path.
 */
class ExecutespecFolderWriterTest {

  private GitHubRepoService gh;
  private ObjectMapper json;
  private CannedHttpClient http;
  private ExecutespecFolderWriter writer;
  private ProjectRepo repo;

  @BeforeEach
  void setUp() {
    gh = mock(GitHubRepoService.class);
    json = new ObjectMapper();
    http = new CannedHttpClient();
    writer = new ExecutespecFolderWriter(http, json, gh);

    repo = new ProjectRepo();
    repo.setGithubRepoFullName("acme/widget");
    // prMode defaults to PR via prModeEnum() when column null — exercised below.

    when(gh.getDefaultBranch(anyString(), anyString())).thenReturn("main");
  }

  @Test
  void writeFiles_rejectsPathsOutsideExecutespecRoot() {
    Map<String, String> outside = Map.of("src/Foo.java", "// not allowed");
    assertThatThrownBy(() -> writer.writeFiles(repo, null, outside, "test", "body", "ghp_test_pat"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(".executespec/");
  }

  @Test
  void readFile_rejectsPathsOutsideExecutespecRoot() {
    assertThatThrownBy(() -> writer.readFile(repo, "src/Foo.java", "ghp_test_pat"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(".executespec/");
  }

  @Test
  void namespaceConfigPath_appliesProjectSlugUnderConfig() {
    assertThat(
            ExecutespecFolderWriter.namespaceConfigPath(
                ".executespec/config/standards.yaml", "acme-web"))
        .isEqualTo(".executespec/config/acme-web/standards.yaml");
  }

  @Test
  void namespaceConfigPath_idempotent_whenAlreadyNamespacedForThisProject() {
    assertThat(
            ExecutespecFolderWriter.namespaceConfigPath(
                ".executespec/config/acme-web/standards.yaml", "acme-web"))
        .isEqualTo(".executespec/config/acme-web/standards.yaml");
  }

  @Test
  void namespaceConfigPath_doesNotTouchNonConfigPaths() {
    assertThat(
            ExecutespecFolderWriter.namespaceConfigPath(".executespec/arch/codemap.md", "acme-web"))
        .isEqualTo(".executespec/arch/codemap.md");
  }

  @Test
  void namespaceConfigPath_skipsNamespacingWhenSlugBlank() {
    assertThat(
            ExecutespecFolderWriter.namespaceConfigPath(".executespec/config/standards.yaml", null))
        .isEqualTo(".executespec/config/standards.yaml");
    assertThat(
            ExecutespecFolderWriter.namespaceConfigPath(".executespec/config/standards.yaml", ""))
        .isEqualTo(".executespec/config/standards.yaml");
  }

  @Test
  void synthesiseBranchName_isAlwaysExecutespecPrefixedAndSlugified() {
    String name = ExecutespecFolderWriter.synthesiseBranchName("ExecuteSpec setup: contract index");
    assertThat(name).startsWith("executespec/");
    assertThat(name).matches("executespec/[a-z0-9-]+");
    // Two calls produce different names (timestamp suffix)
    try {
      Thread.sleep(2);
    } catch (InterruptedException ignored) {
    }
    String name2 =
        ExecutespecFolderWriter.synthesiseBranchName("ExecuteSpec setup: contract index");
    assertThat(name2).isNotEqualTo(name);
  }

  @Test
  void hasDrift_returnsTrue_whenFileMissing() {
    http.queueGet(404, "{}");
    boolean drift = writer.hasDrift(repo, ".executespec/arch/codemap.md", "any-hash", "ghp_test");
    assertThat(drift).isTrue();
  }

  @Test
  void hasDrift_returnsFalse_whenContentHashMatches() {
    String body = "current content";
    http.queueGet(200, contentsResponse(body, "blob-sha-abc"));
    String knownHash = ExecutespecFolderWriter.sha256(body);
    boolean drift = writer.hasDrift(repo, ".executespec/arch/codemap.md", knownHash, "ghp_test");
    assertThat(drift).isFalse();
  }

  @Test
  void hasDrift_returnsTrue_whenContentHashDiffers() {
    http.queueGet(200, contentsResponse("changed content", "blob-sha-xyz"));
    boolean drift = writer.hasDrift(repo, ".executespec/arch/codemap.md", "stale-hash", "ghp_test");
    assertThat(drift).isTrue();
  }

  // End-to-end writeFiles (PR mode)

  @Test
  void writeFiles_prMode_opensSetupBranchPutsFilesAndOpensPr() {
    // Sequence of HTTP responses the writer will see:
    //  1. GET /repos/.../git/refs/heads/main — base SHA lookup
    //  2. POST /repos/.../git/refs           — branch creation
    //  3. GET .../contents/.../codemap.md?ref=main — existing-sha lookup (404 = doesn't exist)
    //  4. PUT .../contents/.../codemap.md   — file write on new branch
    http.queueGet(200, "{\"object\":{\"sha\":\"base-sha-001\"}}");
    http.queuePost(201, "{\"ref\":\"refs/heads/executespec/foo\"}");
    http.queueGet(404, "{}");
    http.queuePut(201, "{\"content\":{\"sha\":\"new-blob\"}}");

    when(gh.createPullRequest(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
        .thenReturn("https://github.com/acme/widget/pull/42");

    Optional<ExecutespecFolderWriter.PullRequestInfo> result =
        writer.writeFiles(
            repo,
            null,
            Map.of(".executespec/arch/codemap.md", "# codemap"),
            "ExecuteSpec: bootstrap codemap",
            "Initial codemap from analysis.",
            "ghp_test");

    assertThat(result).isPresent();
    assertThat(result.get().openedNewPr()).isTrue();
    assertThat(result.get().prUrl()).endsWith("/pull/42");
    assertThat(result.get().filesWritten()).containsExactly(".executespec/arch/codemap.md");
  }

  @Test
  void writeFiles_directPushMode_skipsBranchAndPr() {
    repo.setExecutespecPrMode(ProjectRepo.PrMode.DIRECT_PUSH.name());
    // Direct push only does: getContents (existing-sha) + putContents
    http.queueGet(404, "{}");
    http.queuePut(201, "{\"content\":{\"sha\":\"new-blob\"}}");

    Optional<ExecutespecFolderWriter.PullRequestInfo> result =
        writer.writeFiles(
            repo,
            null,
            Map.of(".executespec/arch/codemap.md", "# codemap"),
            "regen post-merge",
            null,
            "ghp_test");

    assertThat(result).isPresent();
    assertThat(result.get().openedNewPr()).isFalse();
    assertThat(result.get().prUrl()).isNull();
    assertThat(result.get().branchName()).isEqualTo("main");
  }

  /** Encode a content string into a GitHub /contents response body. */
  private String contentsResponse(String content, String sha) {
    String b64 =
        Base64.getEncoder()
            .encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return "{\"content\":\"" + b64 + "\",\"sha\":\"" + sha + "\"}";
  }

  /**
   * Minimal HttpClient that returns canned responses in the order they were queued. Method-keyed
   * queues let tests be precise about which method is answering which call (GET vs PUT vs POST).
   */
  static class CannedHttpClient extends HttpClient {
    private final Deque<HttpResponse<String>> getQueue = new ArrayDeque<>();
    private final Deque<HttpResponse<String>> postQueue = new ArrayDeque<>();
    private final Deque<HttpResponse<String>> putQueue = new ArrayDeque<>();

    void queueGet(int status, String body) {
      getQueue.add(stub(status, body));
    }

    void queuePost(int status, String body) {
      postQueue.add(stub(status, body));
    }

    void queuePut(int status, String body) {
      putQueue.add(stub(status, body));
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
        throws IOException, InterruptedException {
      String method = request.method();
      Deque<HttpResponse<String>> queue =
          switch (method) {
            case "GET" -> getQueue;
            case "POST" -> postQueue;
            case "PUT" -> putQueue;
            default -> throw new IllegalStateException("unexpected method: " + method);
          };
      HttpResponse<String> next = queue.poll();
      if (next == null) {
        throw new IllegalStateException(
            "CannedHttpClient out of " + method + " responses for " + request.uri());
      }
      return (HttpResponse<T>) next;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest r, HttpResponse.BodyHandler<T> h) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<java.net.CookieHandler> cookieHandler() {
      return Optional.empty();
    }

    @Override
    public Optional<java.time.Duration> connectTimeout() {
      return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
      return Redirect.NEVER;
    }

    @Override
    public Optional<java.net.ProxySelector> proxy() {
      return Optional.empty();
    }

    @Override
    public javax.net.ssl.SSLContext sslContext() {
      return null;
    }

    @Override
    public javax.net.ssl.SSLParameters sslParameters() {
      return null;
    }

    @Override
    public Optional<java.net.Authenticator> authenticator() {
      return Optional.empty();
    }

    @Override
    public Version version() {
      return Version.HTTP_1_1;
    }

    @Override
    public Optional<java.util.concurrent.Executor> executor() {
      return Optional.empty();
    }

    @Override
    public java.net.http.WebSocket.Builder newWebSocketBuilder() {
      throw new UnsupportedOperationException();
    }

    private HttpResponse<String> stub(int status, String body) {
      return new HttpResponse<>() {
        @Override
        public int statusCode() {
          return status;
        }

        @Override
        public HttpRequest request() {
          return null;
        }

        @Override
        public Optional<HttpResponse<String>> previousResponse() {
          return Optional.empty();
        }

        @Override
        public java.net.http.HttpHeaders headers() {
          return java.net.http.HttpHeaders.of(Map.of(), (a, b) -> true);
        }

        @Override
        public String body() {
          return body;
        }

        @Override
        public Optional<javax.net.ssl.SSLSession> sslSession() {
          return Optional.empty();
        }

        @Override
        public java.net.URI uri() {
          return null;
        }

        @Override
        public java.net.http.HttpClient.Version version() {
          return Version.HTTP_1_1;
        }
      };
    }
  }
}
