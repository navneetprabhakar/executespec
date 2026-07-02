package com.navneet.executespec.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.navneet.executespec.swebench.SwebenchInstanceLoader;
import com.navneet.executespec.swebench.SwebenchProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkControllerSmokeTest {

  @TempDir Path tempDir;

  @Test
  void previewAndListResolveSweBenchProManifestWithoutStartingRun() throws Exception {
    Path manifest = tempDir.resolve("swepro-mini.json");
    Files.writeString(
        manifest,
        """
        [
          {
            "instance_id": "instance_NodeBB__NodeBB-049989",
            "repo": "NodeBB/NodeBB",
            "base_commit": "abc123",
            "problem_statement": "Fix websocket regression",
            "fail_to_pass": [],
            "pass_to_pass": [],
            "dockerhub_tag": "nodebb-nodebb-049989",
            "repo_language": "javascript",
            "interface": "web"
          }
        ]
        """);

    SwebenchProperties props = new SwebenchProperties();
    props.setEnabled(true);
    props.setSuite("swe-bench-pro");
    props.setInstancesFile(manifest.toString());
    props.setProImageRepository("jefzda/sweap-images");
    props.setBenchProjectId("bench-project");

    SwebenchInstanceLoader loader = new SwebenchInstanceLoader(props);
    BenchmarkController controller =
        new BenchmarkController(mock(BenchmarkGenerationService.class), loader, props);

    Map<String, Object> list = controller.listInstances().getBody();
    Map<String, Object> preview = controller.preview("instance_NodeBB__NodeBB-049989").getBody();

    assertThat(list).containsEntry("count", 1);
    assertThat(preview)
        .containsEntry("instanceId", "instance_NodeBB__NodeBB-049989")
        .containsEntry("repo", "NodeBB/NodeBB")
        .containsEntry("baseCommit", "abc123")
        .containsEntry("image", "jefzda/sweap-images:nodebb-nodebb-049989")
        .containsEntry("enabled", "true")
        .containsEntry("benchProjectId", "bench-project");
  }
}
