package com.navneet.executespec.folder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.navneet.executespec.activity.ActivityEventEmitter;
import com.navneet.executespec.activity.ActivityEventType;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.pr.PullRequestRecordService;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link BootstrapService} (E1.5). Drives the four lifecycle paths end-to-end with a
 * stubbed {@link HttpClient} for the PAT scope check + mocked {@link ExecutespecFolderWriter} for
 * the file ops.
 */
class BootstrapServiceTest {

  private ExecutespecFolderWriter writer;
  private FolderReadmeGenerator readme;
  private ProjectRepoRepository repoRepo;
  private ActivityEventEmitter emitter;
  private PullRequestRecordService prRecordService;
  private CannedHttpClient http;
  private BootstrapService bootstrap;

  private ProjectRepo repo;
  private UUID repoId;
  private UUID projectId;
  private UUID actorUserId;

  @BeforeEach
  void setUp() {
    writer = mock(ExecutespecFolderWriter.class);
    readme = mock(FolderReadmeGenerator.class);
    repoRepo = mock(ProjectRepoRepository.class);
    emitter = mock(ActivityEventEmitter.class);
    prRecordService = mock(PullRequestRecordService.class);
    http = new CannedHttpClient();
    // 2026-05-02 — fix: BootstrapService picked up a 5th constructor argument
    // (PullRequestRecordService, prior to V169 PR-record dispatch) but this test wasn't
    // updated, so the file no longer compiled and blocked the entire test build. Now passes
    // both prRecordService and the canned HttpClient to the package-private test seam.
    bootstrap = new BootstrapService(writer, readme, repoRepo, emitter, prRecordService, http);

    repoId = UUID.randomUUID();
    projectId = UUID.randomUUID();
    actorUserId = UUID.randomUUID();
    repo = new ProjectRepo();
    repo.setId(repoId);
    repo.setGithubRepoFullName("acme/widget");
    when(repoRepo.findById(repoId)).thenReturn(Optional.of(repo));
    when(readme.render(any(), any())).thenReturn("# README");
  }

  // ── Happy path ──────────────────────────────────────────────────────────

  @Test
  void bootstrap_happyPath_opensPrAndMarksBootstrapPrOpen() {
    // PAT scope check returns repo scope
    http.queueGet(200, "{}", Map.of("X-OAuth-Scopes", "repo, read:org"));
    // Folder doesn't exist yet on default branch
    when(writer.readFile(eq(repo), eq(".executespec/README.md"), anyString()))
        .thenReturn(Optional.empty());
    // Writer opens PR
    when(writer.writeFiles(eq(repo), anyString(), anyMap(), anyString(), anyString(), anyString()))
        .thenReturn(
            Optional.of(
                new ExecutespecFolderWriter.PullRequestInfo(
                    "https://github.com/acme/widget/pull/1",
                    "executespec/setup-abc",
                    List.of(".executespec/README.md", ".executespec/config/acme/.gitkeep"),
                    true)));

    bootstrap.bootstrap(repoId, projectId, "Acme Web", actorUserId, "ghp_test_pat");

    ArgumentCaptor<ProjectRepo> savedCaptor = ArgumentCaptor.forClass(ProjectRepo.class);
    verify(repoRepo).save(savedCaptor.capture());
    assertThat(savedCaptor.getValue().getExecutespecFolderStatus())
        .isEqualTo(ProjectRepo.FolderStatus.BOOTSTRAP_PR_OPEN.name());
    assertThat(savedCaptor.getValue().getExecutespecBootstrapPrUrl())
        .isEqualTo("https://github.com/acme/widget/pull/1");
    verify(emitter)
        .emit(
            eq(projectId),
            eq(ActivityEventType.EXECUTESPEC_BOOTSTRAP_INITIATED),
            eq(actorUserId),
            eq("REPO"),
            eq(repoId.toString()),
            anyString(),
            anyMap());
  }

  // ── PAT preflight ───────────────────────────────────────────────────────

  @Test
  void bootstrap_patMissingRepoScope_marksFailedAndDoesNotCallWriter() {
    http.queueGet(200, "{}", Map.of("X-OAuth-Scopes", "read:user, public_repo"));

    bootstrap.bootstrap(repoId, projectId, "Acme Web", actorUserId, "ghp_test_pat");

    ArgumentCaptor<ProjectRepo> savedCaptor = ArgumentCaptor.forClass(ProjectRepo.class);
    verify(repoRepo).save(savedCaptor.capture());
    assertThat(savedCaptor.getValue().getExecutespecFolderStatus())
        .isEqualTo(ProjectRepo.FolderStatus.BOOTSTRAP_FAILED.name());
    verify(writer, never()).writeFiles(any(), any(), any(), any(), any(), any());
    verify(emitter)
        .emit(
            eq(projectId),
            eq(ActivityEventType.EXECUTESPEC_BOOTSTRAP_FAILED),
            eq(actorUserId),
            anyString(),
            anyString(),
            contains("PAT missing required scope"),
            anyMap());
  }

  @Test
  void bootstrap_patRejectedByGitHub_marksFailed() {
    http.queueGet(401, "{\"message\":\"Bad credentials\"}", Map.of());

    bootstrap.bootstrap(repoId, projectId, "Acme Web", actorUserId, "ghp_bad");

    verify(emitter)
        .emit(
            eq(projectId),
            eq(ActivityEventType.EXECUTESPEC_BOOTSTRAP_FAILED),
            any(),
            any(),
            any(),
            anyString(),
            anyMap());
    verify(writer, never()).writeFiles(any(), any(), any(), any(), any(), any());
  }

  @Test
  void checkPatScopes_fineGrainedTokenWithoutScopeHeader_acceptedOptimistically() {
    // Fine-grained tokens don't expose X-OAuth-Scopes; rejecting them
    // would block legitimate users.
    http.queueGet(200, "{}", Map.of()); // no X-OAuth-Scopes header
    BootstrapService.ScopeCheckResult result = bootstrap.checkPatScopes("github_pat_finegrained");
    assertThat(result.ok()).isTrue();
    assertThat(result.observed()).contains("(fine-grained token)");
  }

  @Test
  void checkPatScopes_acceptsRepoColonSubScopes_likeRepoStatus() {
    // GitHub returns "repo:status", "repo_deployment", etc. when only
    // sub-scopes were granted. We accept those because the writer call
    // will surface the real permission error if write isn't allowed.
    http.queueGet(200, "{}", Map.of("X-OAuth-Scopes", "repo:status, public_repo"));
    BootstrapService.ScopeCheckResult result = bootstrap.checkPatScopes("ghp_test");
    assertThat(result.ok()).isTrue();
  }

  // ── Idempotency ─────────────────────────────────────────────────────────

  @Test
  void bootstrap_existingFolderOnRepo_marksBootstrappedWithoutOpeningPr() {
    http.queueGet(200, "{}", Map.of("X-OAuth-Scopes", "repo"));
    when(writer.readFile(eq(repo), eq(".executespec/README.md"), anyString()))
        .thenReturn(
            Optional.of(
                new ExecutespecFolderWriter.FileSnapshot(
                    "# existing README", "blob-sha-existing", "hash-existing")));

    bootstrap.bootstrap(repoId, projectId, "Acme Web", actorUserId, "ghp_test");

    verify(writer, never()).writeFiles(any(), any(), any(), any(), any(), any());
    ArgumentCaptor<ProjectRepo> savedCaptor = ArgumentCaptor.forClass(ProjectRepo.class);
    verify(repoRepo).save(savedCaptor.capture());
    assertThat(savedCaptor.getValue().getExecutespecFolderStatus())
        .isEqualTo(ProjectRepo.FolderStatus.BOOTSTRAPPED.name());
    assertThat(savedCaptor.getValue().getExecutespecFolderSha()).isEqualTo("blob-sha-existing");
    verify(emitter)
        .emit(
            eq(projectId),
            eq(ActivityEventType.EXECUTESPEC_BOOTSTRAP_COMPLETED),
            any(),
            any(),
            any(),
            anyString(),
            anyMap());
  }

  // ── Writer failure path ─────────────────────────────────────────────────

  @Test
  void bootstrap_writerFailure_marksFailedAndEmitsFailedEvent() {
    http.queueGet(200, "{}", Map.of("X-OAuth-Scopes", "repo"));
    when(writer.readFile(eq(repo), anyString(), anyString())).thenReturn(Optional.empty());
    when(writer.writeFiles(any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());

    bootstrap.bootstrap(repoId, projectId, "Acme Web", actorUserId, "ghp_test");

    ArgumentCaptor<ProjectRepo> savedCaptor = ArgumentCaptor.forClass(ProjectRepo.class);
    verify(repoRepo).save(savedCaptor.capture());
    assertThat(savedCaptor.getValue().getExecutespecFolderStatus())
        .isEqualTo(ProjectRepo.FolderStatus.BOOTSTRAP_FAILED.name());
    verify(emitter)
        .emit(
            eq(projectId),
            eq(ActivityEventType.EXECUTESPEC_BOOTSTRAP_FAILED),
            any(),
            any(),
            any(),
            anyString(),
            anyMap());
  }

  // ── Edge cases ──────────────────────────────────────────────────────────

  @Test
  void bootstrap_repoNotFound_isNoop() {
    when(repoRepo.findById(repoId)).thenReturn(Optional.empty());
    bootstrap.bootstrap(repoId, projectId, "Acme Web", actorUserId, "ghp_test");
    verifyNoInteractions(writer, emitter);
  }

  @Test
  void slugify_handlesCommonCases() {
    assertThat(BootstrapService.slugify("Acme Web")).isEqualTo("acme-web");
    assertThat(BootstrapService.slugify("Project!! 1.0 / Frontend"))
        .isEqualTo("project-1-0-frontend");
    assertThat(BootstrapService.slugify(null)).isEqualTo("default");
    assertThat(BootstrapService.slugify("")).isEqualTo("default");
    assertThat(BootstrapService.slugify("---")).isEqualTo("default");
  }

  // ── Helpers ─────────────────────────────────────────────────────────────

  static class CannedHttpClient extends HttpClient {
    private final Deque<HttpResponse<String>> getQueue = new ArrayDeque<>();

    void queueGet(int status, String body, Map<String, String> headers) {
      getQueue.add(stub(status, body, headers));
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> handler)
        throws IOException, InterruptedException {
      HttpResponse<String> next = getQueue.poll();
      if (next == null) throw new IllegalStateException("CannedHttpClient queue empty");
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

    private HttpResponse<String> stub(int status, String body, Map<String, String> headers) {
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
        public HttpHeaders headers() {
          java.util.Map<String, java.util.List<String>> m = new java.util.HashMap<>();
          headers.forEach((k, v) -> m.put(k, java.util.List.of(v)));
          return HttpHeaders.of(m, (a, b) -> true);
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
