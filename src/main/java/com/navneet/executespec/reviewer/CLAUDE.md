# Reviewer and accuracy evidence

Reviewer completion requires a structured verdict and deterministic final build evidence.
Read-only accepted-generation review runs inside a disposable verification session; its opaque
capability fences provider calls and tools throughout the pass.

Accuracy assessment samples source, test and workflow/config evidence through separate bounded
budgets. The config lane includes README variants and requirements files so documentation and
pinned dependencies can be assessed from actual content. Vendored/generated trees remain excluded.
Tree decoration parsing removes literal `[DIR]` and box-drawing prefixes without stripping real
leading R/D/I characters from names. These evidence fixes do not relax acceptance or score caps.
