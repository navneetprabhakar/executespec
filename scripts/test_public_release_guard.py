"""Regression tests for paths that must never enter a public release."""

import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location(
    "public_release_guard", Path(__file__).with_name("check-public-release.py")
)
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


class PublicReleaseGuardTest(unittest.TestCase):
    def test_rejects_private_generated_and_state_artifacts(self):
        for path in (
            ".codex-work/auth.json", ".claude/worktrees/run.txt", "output/audit.pdf",
            "outputs/deck.pptx", "workspace/run/files.json", "infra/benchmark/tfplan-upsize",
            "infra/release.tfplan", "infra/terraform.tfstate.backup", "nested/.env.production",
            "src/main/resources/application-local.yml", "service.key", "identity.p12",
            "src/main/resources/static/index.html", "scripts/web-agent-release-results/auth.png",
        ):
            with self.subTest(path=path):
                self.assertIsNotNone(guard.forbidden_path(path))

    def test_keeps_source_templates_and_canonical_assets(self):
        for path in (
            ".env.template", ".env.example", "frontend/public/logo.png",
            "src/main/java/com/navneet/executespec/project/workspace/Workspace.java",
            "src/main/resources/db/migration/V1__initial.sql", "infra/main.tf",
            "scripts/check-public-release.py", "src/test/resources/test-output.txt",
        ):
            with self.subTest(path=path):
                self.assertIsNone(guard.forbidden_path(path))


if __name__ == "__main__":
    unittest.main()
