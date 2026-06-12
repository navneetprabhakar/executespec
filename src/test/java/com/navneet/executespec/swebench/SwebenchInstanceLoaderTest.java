package com.navneet.executespec.swebench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

class SwebenchInstanceLoaderTest {

  @TempDir Path tempDir;

  @Test
  void loadsSweBenchProFieldsAndResolvesDockerhubImage() throws Exception {
    Path instances = tempDir.resolve("swepro.json");
    Files.writeString(
        instances,
        """
        [
          {
            "instance_id": "instance_NodeBB__NodeBB-049989",
            "repo": "NodeBB/NodeBB",
            "base_commit": "abc123",
            "problem_statement": "Fix websocket regression",
            "fail_to_pass": ["test/socket.js::works"],
            "pass_to_pass": ["test/smoke.js::still_works"],
            "patch": "diff --git a/file b/file",
            "test_patch": "diff --git a/test b/test",
            "dockerhub_tag": "nodebb-nodebb-049989",
            "repo_language": "javascript",
            "interface": "web"
          }
        ]
        """);

    SwebenchProperties props = new SwebenchProperties();
    props.setInstancesFile(instances.toString());
    props.setSuite("swe-bench-pro");
    props.setProImageRepository("jefzda/sweap-images");

    SwebenchInstanceLoader loader = new SwebenchInstanceLoader(props);
    SwebenchInstanceLoader.SwebenchInstance instance =
        loader.findById("instance_NodeBB__NodeBB-049989");

    assertThat(instance.isSweBenchPro()).isTrue();
    assertThat(instance.failToPass()).containsExactly("test/socket.js::works");
    assertThat(instance.passToPass()).containsExactly("test/smoke.js::still_works");
    assertThat(instance.repoLanguage()).isEqualTo("javascript");
    assertThat(instance.interfaceName()).isEqualTo("web");
    assertThat(loader.imageNameFor(instance.instanceId()))
        .isEqualTo("jefzda/sweap-images:nodebb-nodebb-049989");
  }

  @Test
  void rejectsSweBenchProRowsWithoutDockerhubTag() throws Exception {
    Path instances = tempDir.resolve("swepro-missing-image.json");
    Files.writeString(
        instances,
        """
        [
          {
            "instance_id": "instance_missing_image",
            "repo": "owner/repo",
            "base_commit": "abc123",
            "problem_statement": "Fix issue"
          }
        ]
        """);

    SwebenchProperties props = new SwebenchProperties();
    props.setInstancesFile(instances.toString());
    props.setSuite("swe-bench-pro");

    SwebenchInstanceLoader loader = new SwebenchInstanceLoader(props);

    assertThatThrownBy(() -> loader.imageNameFor("instance_missing_image"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("missing dockerhub_tag");
  }
}
