# Generated test execution evidence

`TestGenerationAdmissionService` distinguishes the plan's product-diff fingerprint from the
canonical Git-tree workspace generation. They are different hashes with different purposes.
A verified execution summary carries its canonical generation, execution id, and server launch
time. `HeadlessTestExecutor` captures the canonical generation before and after execution and
rejects drift. Live execution additionally requires a current, unexpired preview bound to that
generation; `TestPreviewCoordinator` verifies that the same deployed artifact survived the runner.

`PreviewSession` source binding is created during isolated build-context staging, never inferred
from whichever canonical tree exists when a late preview/test callback arrives. Historical unbound
previews cannot mint new functional proof. The host-network live-preview runner remains gated off
until controlled egress replaces that transport.

`ConcreteTestEvidenceAdmission` in `report/` serializes exact-generation, launch-ordered settlement
through V402 fields. Delayed or duplicate events cannot rewrite newer evidence. Optional narrative
report generation stays inside the test-completion boundary; accounting also retains terminal usage
for bounded late responses. Empty/unavailable summaries never imply executed tests.
