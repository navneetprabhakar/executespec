package com.navneet.executespec.agentsession;

import java.util.Arrays;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** Operator endpoint for optional live orchestrator routing prompt evals. */
@RestController
@RequestMapping("/api/admin/eval/orchestrator-routing")
@RequiredArgsConstructor
public class OrchestratorRoutingEvalController {

  private final OrchestratorRoutingPromptEvalRunner runner;

  @PostMapping("/run")
  public ResponseEntity<OrchestratorRoutingPromptEvalRunner.EvalReport> run(
      @RequestParam(name = "providers", required = false) String providers,
      @RequestParam(name = "limit", required = false) Integer limit) {
    List<String> providerList =
        providers == null || providers.isBlank()
            ? List.of("openai", "anthropic", "gemini", "deepseek")
            : Arrays.stream(providers.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    return ResponseEntity.ok(runner.run(providerList, limit));
  }
}
