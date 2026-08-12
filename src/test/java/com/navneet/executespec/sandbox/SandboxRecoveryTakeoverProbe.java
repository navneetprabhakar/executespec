package com.navneet.executespec.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Separate-process takeover probe used only by the real-container recovery qualification. */
public final class SandboxRecoveryTakeoverProbe {

  private SandboxRecoveryTakeoverProbe() {}

  public static void main(String[] args) {
    if (args.length != 9) {
      throw new IllegalArgumentException(
          "expected mode,jdbc,user,password,sandboxRoot,agentRoot,sandboxId,commandId,expected");
    }
    String mode = args[0];
    DriverManagerDataSource dataSource = new DriverManagerDataSource();
    dataSource.setDriverClassName("org.postgresql.Driver");
    dataSource.setUrl(args[1]);
    dataSource.setUsername(args[2]);
    dataSource.setPassword(args[3]);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    UUID sandboxId = UUID.fromString(args[6]);

    SandboxProperties properties = new SandboxProperties();
    properties.setWorkspaceRoot(args[4]);
    properties.setImage("executespec/sandbox-toolchain:litmus-20260802");
    properties.setLeaseDuration(Duration.ofMinutes(5));

    if ("JANITOR".equals(mode)) {
      VerificationSandboxWorkspaceJanitor janitor =
          new VerificationSandboxWorkspaceJanitor(
              new VerificationSandboxWorkspaceCleanupStore(jdbc), properties);
      require(janitor.sweep(1) == 1, "replacement janitor did not complete one claim");
      String state =
          jdbc.queryForObject(
              "SELECT status FROM run_sandbox_workspace_cleanup WHERE sandbox_id=?",
              String.class,
              sandboxId);
      require(args[8].equals(state), "unexpected cleanup state " + state);
      System.out.println("TAKEOVER_PROBE_PASSED mode=JANITOR sandbox=" + sandboxId);
      return;
    }

    FeatureFlagService flags =
        new FeatureFlagService(null, null, null, null) {
          @Override
          public boolean isEnabled(String key) {
            return true;
          }
        };
    RunSandboxStore store = new RunSandboxStore(jdbc);
    ContainerStrictV1Runtime runtime =
        new ContainerStrictV1Runtime(properties, flags, Path.of(args[5]));
    RunSandboxRuntimeDispatcher dispatcher =
        new RunSandboxRuntimeDispatcher(store, flags, List.of(runtime));

    if (!"-".equals(args[7])) {
      TransactionTemplate transaction =
          new TransactionTemplate(new DataSourceTransactionManager(dataSource));
      SandboxCommandJournalStore.RecoveryResult recovered =
          transaction.execute(
              ignored -> new SandboxCommandJournalStore(jdbc).recoverStaleCommands(10));
      require(recovered.total() >= 1, "replacement process recovered no command");
      String commandState =
          jdbc.queryForObject(
              "SELECT state FROM sandbox_command_execution WHERE id=?",
              String.class,
              UUID.fromString(args[7]));
      require(args[8].split(":", 2)[0].equals(commandState), "unexpected command " + commandState);
    }

    RunSandbox terminal =
        dispatcher
            .reconcileOrphan(
                store.get(sandboxId).orElseThrow(),
                "replacement-jvm:" + ProcessHandle.current().pid(),
                Duration.ofMinutes(5))
            .orElseThrow();
    String expectedSandbox = args[8].contains(":") ? args[8].split(":", 2)[1] : args[8];
    require(
        expectedSandbox.equals(terminal.state().name()), "unexpected sandbox " + terminal.state());
    require(!runtime.alive(terminal), "container survived replacement-JVM reconciliation");
    System.out.println(
        "TAKEOVER_PROBE_PASSED mode=RECOVER sandbox="
            + sandboxId
            + " owner="
            + ProcessHandle.current().pid());
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
