package com.navneet.executespec.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.navneet.executespec.integration.RunWorkspaceAuthorityService;
import com.navneet.executespec.repo.GitProcessAuth;
import com.navneet.executespec.tool.GitTool;
import com.navneet.executespec.tool.GitToolResult;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real Git + PostgreSQL proof for exact multi-repository publication and lost settlement. */
@Tag("integration")
@Testcontainers
class PublicationRealGitPartialEffectPostgresTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("pgvector/pgvector:pg16")
          .withDatabaseName("publication_real_git")
          .withUsername("test")
          .withPassword("test");

  @TempDir Path temp;

  @Test
  void exactPreparedCommitsReachTwoRealRemotesAndLostSecondSettlementStaysIndeterminate()
      throws Exception {
    Path workspaceRoot = temp.resolve("workspace");
    String runId = "real-git-publication-run";
    ProviderRemotes providerRemotes = ProviderRemotes.fromEnvironment();
    Path remotes = Files.createDirectories(temp.resolve("remotes"));
    RepoFixture first =
        repository(
            workspaceRoot,
            runId,
            remotes,
            "repo-one",
            providerRemotes == null ? null : providerRemotes.firstRemote(),
            providerRemotes == null ? null : providerRemotes.branchPrefix() + "-repo-one",
            providerRemotes == null ? null : providerRemotes.pat());
    RepoFixture second =
        repository(
            workspaceRoot,
            runId,
            remotes,
            "repo-two",
            providerRemotes == null ? null : providerRemotes.secondRemote(),
            providerRemotes == null ? null : providerRemotes.branchPrefix() + "-repo-two",
            providerRemotes == null ? null : providerRemotes.pat());

    DriverManagerDataSource dataSource = dataSource();
    JdbcTemplate bootstrapJdbc = new JdbcTemplate(dataSource);
    bootstrapJdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    JdbcTemplate jdbc =
        new JdbcTemplate(new SingleConnectionDataSource(dataSource.getConnection(), true));

    UUID preparationId = UUID.randomUUID();
    UUID ownerToken = UUID.randomUUID();
    String generation = "a".repeat(64);
    String evidenceJson = "{}";
    String evidenceDigest = PublicationPreparationService.sha256(evidenceJson);
    String reportId = "real-git-publication-report";
    Instant now = Instant.now();
    UUID attemptId = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO pipeline_run(run_id,status,run_mode,build_protocol_version) "
            + "VALUES (?,'COMPLETE','BUILD','SHARED_KERNEL_V2')",
        runId);
    jdbc.update(
        "INSERT INTO validation_report(id,run_id,workspace_generation,status,updated_at) "
            + "VALUES (?,?,?,'COMPLETE',?)",
        reportId,
        runId,
        generation,
        Timestamp.from(now));
    jdbc.execute("SET session_replication_role = replica");
    try {
      jdbc.update(
          """
INSERT INTO workspace_generation_attempt
  (id,run_id,task_id,task_index,attempt_number,attempt_kind,state,workspace_root,
   before_generation,candidate_generation,repositories_json,
   ignored_artifact_baseline_json,ignored_artifact_baseline_digest,authority_digest,
   task_contract_digest,workspace_lease_token,llm_operation_id,llm_attempt_number,
   llm_lease_token,before_checkpoint_kind,before_checkpoint_key,before_checkpoint_digest,
   before_checkpoint_completed_at,checkpoint_kind,checkpoint_key,checkpoint_generation,
   checkpoint_digest,checkpoint_completed_at,resolved_at,build_protocol_version,
   verification_command_protocol_version)
VALUES (?,?,'T1',0,1,'BUILD_TASK','ACCEPTED','/tmp/real-git-publication',repeat('0',64),?,
   '[]'::jsonb,'{}'::jsonb,repeat('1',64),repeat('2',64),repeat('3',64),?,?,1,?,
   'GCS','gs://qualification/before',repeat('4',64),NOW()-INTERVAL '1 minute',
   'GCS','gs://qualification/exact',?,?,?,NOW(),'SHARED_KERNEL_V2',2)
""",
          attemptId,
          runId,
          generation,
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          generation,
          "b".repeat(64),
          Timestamp.from(now));
      jdbc.update(
          "UPDATE pipeline_run SET accepted_workspace_generation=?,accepted_task_index=0,"
              + "accepted_generation_attempt_id=?,accepted_checkpoint_kind='GCS',"
              + "accepted_checkpoint_key='gs://qualification/exact',"
              + "accepted_checkpoint_digest=?,accepted_checkpoint_completed_at=? WHERE run_id=?",
          generation,
          attemptId,
          "b".repeat(64),
          Timestamp.from(now),
          runId);
      jdbc.update(
          "INSERT INTO workspace_publication_preparation(id,run_id,state,accepted_generation,"
              + "accepted_attempt_id,accepted_checkpoint_kind,accepted_checkpoint_key,accepted_checkpoint_digest,"
              + "accepted_checkpoint_completed_at,validation_report_id,validation_evidence_json,"
              + "validation_evidence_digest,validation_report_updated_at,workspace_authority_owner,expected_repository_count,preparing_at,prepared_at)"
              + " VALUES (?,?,'PREPARED',?,?,'GCS','gs://qualification/exact',?,?,?,"
              + " ?,?,?,?,2,NOW(),NOW())",
          preparationId,
          runId,
          generation,
          attemptId,
          "b".repeat(64),
          Timestamp.from(now),
          reportId,
          evidenceJson,
          evidenceDigest,
          Timestamp.from(now),
          ownerToken);
      insertPreparedReceipt(jdbc, preparationId, first);
      insertPreparedReceipt(jdbc, preparationId, second);
    } finally {
      jdbc.execute("SET session_replication_role = origin");
    }
    jdbc.update(
        "INSERT INTO"
            + " run_workspace_authority(run_id,authority_type,owner_token,lease_expires_at,heartbeat_at,updated_at)"
            + " VALUES (?,'PUBLICATION',?,NOW()+INTERVAL '5 minutes',NOW(),NOW())",
        runId,
        ownerToken);

    PublicationPreparationStore store = new PublicationPreparationStore(jdbc);
    List<PublicationPreparationStore.PreparedRepository> prepared =
        store.preparedRepositories(preparationId);
    PublicationPreparationService.RequiredPreparation required =
        new PublicationPreparationService.RequiredPreparation(
            store.findPrepared(runId, generation, evidenceDigest).orElseThrow(), prepared);
    RunWorkspaceAuthorityService.Lease lease = mock(RunWorkspaceAuthorityService.Lease.class);
    when(lease.runId()).thenReturn(runId);
    when(lease.ownerToken()).thenReturn(ownerToken);
    when(lease.authority()).thenReturn(RunWorkspaceAuthorityService.Authority.PUBLICATION);
    PublicationAdmissionService.Admission admission =
        new PublicationAdmissionService.Admission(
            generation, "PUBLICATION_ELIGIBLE", lease, required);
    GitTool git = new GitTool(mock(StringRedisTemplate.class));
    setWorkspaceRoot(git, workspaceRoot);
    PublicationDispatchService dispatcher = new PublicationDispatchService(store);

    PublicationPreparationStore.PreparedRepository firstPrepared = prepared(prepared, first.id());
    GitToolResult firstResult =
        dispatcher.push(
            admission,
            firstPrepared,
            first.remoteUrl(),
            null,
            () ->
                git.pushPreparedCommit(
                    runId,
                    first.subPath(),
                    first.remoteUrl(),
                    first.pat(),
                    firstPrepared.publicationBranch(),
                    firstPrepared.commitId(),
                    null));
    assertThat(firstResult.isOk()).isTrue();
    assertThat(remoteBranch(first)).isEqualTo(first.preparedCommit());
    assertThat(remoteBranch(first)).isNotEqualTo(first.unboundCommit());
    assertThat(publicationState(jdbc, first.id())).isEqualTo("PUBLISHED");

    ProcessBuilder killedBuilder =
        new ProcessBuilder(
                javaBinary(),
                "-cp",
                testClasspath(),
                PublicationRealGitCrashProbe.class.getName(),
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                runId,
                preparationId.toString(),
                second.id().toString(),
                workspaceRoot.toString(),
                second.subPath(),
                second.remoteUrl(),
                evidenceDigest,
                generation,
                ownerToken.toString())
            .redirectErrorStream(true);
    if (second.pat() != null) {
      killedBuilder.environment().put("ES_R17_GITHUB_PAT", second.pat());
    }
    Process killed = killedBuilder.start();
    assertThat(killed.waitFor(30, TimeUnit.SECONDS)).isTrue();
    String killedOutput =
        new String(killed.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(killed.exitValue()).as(killedOutput).isEqualTo(91);
    assertThat(remoteBranch(second)).isEqualTo(second.preparedCommit());
    assertThat(remoteBranch(second)).isNotEqualTo(second.unboundCommit());
    assertThat(publicationState(jdbc, second.id())).isEqualTo("DISPATCHED");

    String recoveryOutput = runRecoveryProbe();
    assertThat(recoveryOutput).contains("attemptsIndeterminate=1");
    assertThat(publicationState(jdbc, second.id())).isEqualTo("INDETERMINATE");
    AtomicBoolean replayedRemoteEffect = new AtomicBoolean();
    assertThatThrownBy(
            () ->
                dispatcher.push(
                    admission,
                    prepared(prepared, second.id()),
                    second.remoteUrl(),
                    null,
                    () -> {
                      replayedRemoteEffect.set(true);
                      return GitToolResult.ok("MUST_NOT_RUN");
                    }))
        .hasMessage("PUBLICATION_ATTEMPT_OUTCOME_UNRESOLVED:INDETERMINATE");
    assertThat(replayedRemoteEffect).isFalse();
    assertThat(remoteBranch(second)).isEqualTo(second.preparedCommit());
    assertThat(publicationState(jdbc, first.id())).isEqualTo("PUBLISHED");
    if (providerRemotes != null) {
      System.out.printf(
          "R17_PROVIDER_REF remote=%s branch=%s sha=%s state=PUBLISHED%n",
          first.identity(), first.branch(), first.preparedCommit());
      System.out.printf(
          "R17_PROVIDER_REF remote=%s branch=%s sha=%s state=INDETERMINATE%n",
          second.identity(), second.branch(), second.preparedCommit());
    }
  }

  private RepoFixture repository(
      Path workspaceRoot,
      String runId,
      Path remotes,
      String name,
      String providerRemote,
      String providerBranch,
      String providerPat)
      throws Exception {
    String subPath = "repos/" + name;
    Path worktree = Files.createDirectories(workspaceRoot.resolve(runId).resolve(subPath));
    boolean providerMode = providerRemote != null;
    Path bare = providerMode ? null : remotes.resolve(name + ".git");
    if (!providerMode) git(temp, "init", "--bare", bare.toString());
    git(temp, "init", "-b", "main", worktree.toString());
    git(worktree, "config", "user.name", "ExecuteSpec Qualification");
    git(worktree, "config", "user.email", "qualification@executespec.ai");
    Files.writeString(worktree.resolve("artifact.txt"), "base\n");
    git(worktree, "add", "artifact.txt");
    git(worktree, "commit", "-m", "base");
    String parent = git(worktree, "rev-parse", "HEAD");
    if (!providerMode) {
      git(worktree, "push", bare.toUri().toString(), "HEAD:refs/heads/main");
    }
    Files.writeString(worktree.resolve("artifact.txt"), "prepared " + name + "\n");
    git(worktree, "add", "artifact.txt");
    git(worktree, "commit", "-m", "prepared " + name);
    String prepared = git(worktree, "rev-parse", "HEAD");
    String tree = git(worktree, "rev-parse", "HEAD^{tree}");
    Files.writeString(worktree.resolve("artifact.txt"), "unbound " + name + "\n");
    git(worktree, "add", "artifact.txt");
    git(worktree, "commit", "-m", "unbound " + name);
    String unbound = git(worktree, "rev-parse", "HEAD");
    git(worktree, "reset", "--hard", parent);
    String remoteUrl =
        providerMode ? providerRemote : "https://github.com/qualification/" + name + ".git";
    String identity =
        providerMode
            ? PublicationTargetResolver.canonicalRemoteIdentity("GITHUB:expected", remoteUrl)
            : "GITHUB:qualification/" + name;
    String branch = providerMode ? providerBranch : "executespec/qualification";
    if (!providerMode) {
      String rewriteBase = remotes.toUri().toString();
      git(
          worktree,
          "config",
          "url." + rewriteBase + ".insteadOf",
          "https://github.com/qualification/");
    }
    return new RepoFixture(
        UUID.randomUUID(),
        name,
        subPath,
        identity,
        remoteUrl,
        bare,
        worktree,
        branch,
        providerPat,
        parent,
        tree,
        prepared,
        unbound);
  }

  private static void insertPreparedReceipt(
      JdbcTemplate jdbc, UUID preparationId, RepoFixture fixture) {
    String message = "prepared " + fixture.name();
    jdbc.update(
        "INSERT INTO workspace_publication_repository_receipt(id,preparation_id,state,"
            + "repository_identity,repository_identity_digest,relative_worktree_path,"
            + "publication_branch,provision_required,commit_message,commit_message_digest,"
            + "committed_at,parent_commit_id,tree_id,commit_id,has_changes,prepared_at) VALUES "
            + "(?,?,'PREPARED',?,?,?,?,FALSE,?,?,NOW(),?,?,?,TRUE,NOW())",
        fixture.id(),
        preparationId,
        fixture.identity(),
        PublicationPreparationService.sha256(fixture.identity()),
        fixture.subPath(),
        fixture.branch(),
        message,
        PublicationPreparationService.sha256(message),
        fixture.parentCommit(),
        fixture.tree(),
        fixture.preparedCommit());
  }

  private static PublicationPreparationStore.PreparedRepository prepared(
      List<PublicationPreparationStore.PreparedRepository> repositories, UUID id) {
    return repositories.stream()
        .filter(repository -> repository.repositoryReceiptId().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private static String publicationState(JdbcTemplate jdbc, UUID receiptId) {
    return jdbc.queryForObject(
        "SELECT state FROM workspace_publication_attempt WHERE repository_receipt_id=? "
            + "ORDER BY attempt_number DESC LIMIT 1",
        String.class,
        receiptId);
  }

  private static String remoteBranch(RepoFixture fixture) throws Exception {
    if (fixture.bare() == null) {
      ProcessBuilder processBuilder =
          GitProcessAuth.git(
              fixture.pat(),
              List.of(
                  "-C",
                  fixture.worktree().toString(),
                  "ls-remote",
                  fixture.remoteUrl(),
                  "refs/heads/" + fixture.branch()));
      processBuilder.redirectErrorStream(true);
      Process process = processBuilder.start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
      assertThat(process.exitValue()).as(output).isZero();
      assertThat(output).as("provider branch must exist").isNotBlank();
      return output.split("\\s+", 2)[0];
    }
    return git(
        fixture.bare(),
        "--git-dir",
        fixture.bare().toString(),
        "rev-parse",
        "refs/heads/" + fixture.branch());
  }

  private static String runRecoveryProbe() throws Exception {
    Process process =
        new ProcessBuilder(
                javaBinary(),
                "-cp",
                testClasspath(),
                PublicationRecoveryTakeoverProbe.class.getName(),
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword(),
                Long.toString(Instant.now().plusSeconds(2).toEpochMilli()))
            .redirectErrorStream(true)
            .start();
    assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.exitValue()).as(output).isZero();
    assertThat(output).contains("PUBLICATION_TAKEOVER_PASSED");
    return output;
  }

  private static DriverManagerDataSource dataSource() {
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(POSTGRES.getJdbcUrl());
    dataSource.setUsername(POSTGRES.getUsername());
    dataSource.setPassword(POSTGRES.getPassword());
    return dataSource;
  }

  private static void setWorkspaceRoot(GitTool git, Path root) throws Exception {
    Field field = GitTool.class.getDeclaredField("workspaceRoot");
    field.setAccessible(true);
    field.set(git, root.toString());
  }

  private static String git(Path directory, String... arguments) throws Exception {
    java.util.ArrayList<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
    assertThat(process.waitFor(20, TimeUnit.SECONDS)).isTrue();
    String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.exitValue()).as(stderr).isZero();
    return stdout.trim();
  }

  private static String javaBinary() {
    return Path.of(System.getProperty("java.home"), "bin", "java").toString();
  }

  private static String testClasspath() {
    return System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
  }

  private record RepoFixture(
      UUID id,
      String name,
      String subPath,
      String identity,
      String remoteUrl,
      Path bare,
      Path worktree,
      String branch,
      String pat,
      String parentCommit,
      String tree,
      String preparedCommit,
      String unboundCommit) {}

  private record ProviderRemotes(
      String firstRemote, String secondRemote, String branchPrefix, String pat) {
    private static ProviderRemotes fromEnvironment() {
      String first = System.getenv("ES_R17_GITHUB_REMOTE_ONE");
      String second = System.getenv("ES_R17_GITHUB_REMOTE_TWO");
      String prefix = System.getenv("ES_R17_BRANCH_PREFIX");
      String pat = System.getenv("ES_R17_GITHUB_PAT");
      boolean any = hasText(first) || hasText(second) || hasText(prefix) || hasText(pat);
      if (!any) return null;
      if (!hasText(first) || !hasText(second) || !hasText(prefix) || !hasText(pat)) {
        throw new IllegalStateException("R17_PROVIDER_CONFIGURATION_INCOMPLETE");
      }
      if (!prefix.matches("executespec/r17-[a-zA-Z0-9._/-]+") || prefix.contains("..")) {
        throw new IllegalStateException("R17_PROVIDER_BRANCH_PREFIX_INVALID");
      }
      return new ProviderRemotes(first, second, prefix, pat);
    }

    private static boolean hasText(String value) {
      return value != null && !value.isBlank();
    }
  }
}
