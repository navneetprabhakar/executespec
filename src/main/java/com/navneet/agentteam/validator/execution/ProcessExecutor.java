package com.navneet.agentteam.validator.execution;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ProcessExecutor {

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessCommand {
        private List<String> args;
        private Path workingDir;
        @Builder.Default
        private Duration timeout = Duration.ofSeconds(30);
        @Builder.Default
        private Map<String, String> environment = Map.of();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessResult {
        private int exitCode;
        private String output;
        private Duration duration;
        private boolean timedOut;
        private String error;

        public boolean isSuccess() {
            return exitCode == 0 && !timedOut && error == null;
        }

        public static ProcessResult timeout(Duration timeout) {
            return ProcessResult.builder()
                    .exitCode(-1)
                    .output("Process timed out after " + timeout.toSeconds() + "s")
                    .timedOut(true)
                    .duration(timeout)
                    .build();
        }

        public static ProcessResult error(Exception e) {
            return ProcessResult.builder()
                    .exitCode(-1)
                    .output("")
                    .error(e.getMessage())
                    .duration(Duration.ZERO)
                    .build();
        }
    }

    public ProcessResult execute(ProcessCommand command) {
        Instant start = Instant.now();
        try {
            ProcessBuilder pb = new ProcessBuilder(command.getArgs())
                    .directory(command.getWorkingDir().toFile())
                    .redirectErrorStream(true);

            pb.environment().putAll(command.getEnvironment());

            Process process = pb.start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean completed = process.waitFor(
                    command.getTimeout().toMillis(), TimeUnit.MILLISECONDS);

            Duration elapsed = Duration.between(start, Instant.now());

            if (!completed) {
                process.destroyForcibly();
                log.warn("Process timed out after {}s: {}", command.getTimeout().toSeconds(),
                        String.join(" ", command.getArgs()));
                return ProcessResult.timeout(command.getTimeout());
            }

            return ProcessResult.builder()
                    .exitCode(process.exitValue())
                    .output(output)
                    .duration(elapsed)
                    .timedOut(false)
                    .build();

        } catch (java.io.IOException e) {
            // Binary not found (e.g. mypy, ruff not installed) — expected for optional toolchains
            log.debug("Process not found: {} — {}", String.join(" ", command.getArgs()), e.getMessage());
            return ProcessResult.error(e);
        } catch (Exception e) {
            log.error("Process execution failed: {}", String.join(" ", command.getArgs()), e);
            return ProcessResult.error(e);
        }
    }
}
