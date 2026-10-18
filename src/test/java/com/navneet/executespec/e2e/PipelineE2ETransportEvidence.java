package com.navneet.executespec.e2e;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.web.client.RestClientResponseException;

/** Opt-in fixture diagnostics only; preserves the original provider exception and authorization. */
@Aspect
final class PipelineE2ETransportEvidence {
  @Around("execution(* com.navneet.executespec.agent.OpenAiNativeToolClient.call(..))")
  Object capture(ProceedingJoinPoint call) throws Throwable {
    try {
      return call.proceed();
    } catch (Throwable failure) {
      String runId = System.getenv("E2E_RUN_ID");
      if (runId != null) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
          if (!(cause instanceof RestClientResponseException http)) continue;
          try {
            String body = http.getResponseBodyAsString();
            for (var entry : System.getenv().entrySet()) {
              if ((entry.getKey().endsWith("_API_KEY") || entry.getKey().endsWith("_KEY"))
                  && entry.getValue().length() > 8) {
                body = body.replace(entry.getValue(), "[REDACTED]");
              }
            }
            body = body.replaceAll("(?i)Bearer\\s+[A-Za-z0-9_.-]+", "Bearer [REDACTED]");
            if (body.length() > 4000) body = body.substring(0, 4000);
            Path target = Path.of("target/live-e2e-evidence", runId + "-transport.jsonl");
            Files.createDirectories(target.getParent());
            Files.writeString(
                target,
                new ObjectMapper()
                        .writeValueAsString(
                            Map.of("status", http.getStatusCode().value(), "body", body))
                    + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
          } catch (Exception captureFailure) {
            failure.addSuppressed(captureFailure);
          }
          break;
        }
      }
      throw failure;
    }
  }
}
