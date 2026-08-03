# Preview render

The `previewrender` package generates reloadable, static visual previews for changed UI surfaces
after a run reaches a terminal state. It does not execute or certify the generated application.

## Invariants

- A new render job is admitted only for a terminal run. An already-active job remains reloadable
  without repeating admission.
- `PreviewSurfaceDiscovery` returns only directly changed screens, screens that directly import a
  changed UI/support file, or same-repository screens affected by changed theme/token files.
  Backend-only diffs cannot expose an untouched screen, and normalized multi-repository paths never
  leak dependencies or token context across repository boundaries.
- `PreviewRenderRouteResolver` resolves the route once before the job row is created. Exact run pins
  require and retain the persisted provider/model pair. Unpinned runs resolve the WORKHORSE route
  using `billingOrgId`, falling back to `orgId`, so payer-organization matrix overrides apply.
- Every surface uses the job's persisted provider/model and a route-aware execution policy with
  `strictProvider=true` and provider failover disabled. A render job cannot silently substitute a
  provider or model between surfaces.
- Parallel surface generation shares one monotonic job-wide deadline. Unfinished tasks are
  cancelled at that boundary instead of receiving a fresh timeout per surface.
- The prompt requires representative populated data unless the source itself is an empty/loading/
  error state. The Web UI permanently labels the result as a simulated visual preview and embeds it
  with `sandbox="allow-scripts"` only; it is not runtime or functional-test evidence.
- Archived-workspace rendering remains unsupported as durable proof while the archive lacks the Git
  baseline needed to reconstruct an immutable affected-file set.

Key files: `PreviewRenderService`, `PreviewSurfaceDiscovery`, `PreviewRenderRouteResolver`,
`PreviewRenderAgent`, and `PreviewRenderController`.
