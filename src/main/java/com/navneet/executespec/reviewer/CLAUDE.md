# Reviewer and accuracy evidence

Reviewer completion requires a structured verdict and deterministic final build evidence.
Read-only accepted-generation review runs inside a disposable verification session; its opaque
capability fences provider calls and tools throughout the pass.

Accuracy assessment samples source, test and workflow/config evidence through separate bounded
budgets. The config lane includes README variants and requirements files so documentation and
pinned dependencies can be assessed from actual content. Vendored/generated trees remain excluded.
Tree decoration parsing removes literal `[DIR]` and box-drawing prefixes without stripping real
leading R/D/I characters from names. These evidence fixes do not relax acceptance or score caps.

Read-only semantic attestation receives fresh server-run maintained-test evidence before either
pass. `ReviewerVerificationEvidence` uses only the explicit live verification capability, checks
the product generation before and after the server-resolved suite, and exposes honest unavailable
or failing evidence without granting runTests/shell/edit authority to the model. Green tests do
not excuse semantic defects. This prevents compile-only reviewer tools from creating a false
"tests were never executed" issue when the exact accepted generation has proven passing tests.

Workspace source/test/config evidence uses `tool/RepositoryTextCodec`: strict UTF-8 first,
BOM-aware UTF-16, and Latin-1 fallback only for malformed legacy bytes. Whole-file reads, line
ranges, indexed search, Java fallback search, and compatibility reference search share this
decoding policy so valid Unicode literals cannot become different strings during assessment.

Accuracy assessment also receives `ValidationAssessmentExecutionFacts` from the suite the validation
phase already executed. Facts require the same live capability and unchanged before/after product
generation, exact successful task order, and honest suite counts/exit status. Maintained-suite
execution is never represented as independently generated product verification and never fills
the independent TESTS scoring slot by itself. Missing proof remains explicitly unavailable.

The bounded config/artifact evidence lane prioritizes text artifacts explicitly named in the
customer's original request, even when filenames are not backtick quoted. Tree existence, exact
path boundaries, traversal/vendor exclusions and the existing eight-file/30k budget remain in
force. Line counts and truncation completeness are shown so one-line output requirements can be
judged from the actual file rather than its filename alone.
