# fe-signal-console — raw findings

Reviewed: `frontend/src/signal/` (33 files), `frontend/src/console/` (3), `frontend/src/design-system/` (3), `frontend/src/index.css`, `frontend/src/tokens.css`. Every file read in full; every dead/unused claim below verified by repo-wide grep over `frontend/src`. Branch `benchmark`, working tree as-is, 2026-06-10.

---

## 1. Inventory — per area; orphan verdicts

Legend: **LIVE** = has at least one importer reachable from `App.tsx`/`main.tsx`. **ORPHAN** = zero importers anywhere in `frontend/src`. **DEAD-EXPORT** = file is imported, but the named export has no importer.

### signal/pages (5 files, 6,169 LOC)

| File | LOC | Verdict |
|---|---|---|
| `ExtraPages.tsx` | 2,770 | **~83% dead.** LIVE exports (imported by `App.tsx:52-58`): `TrustPage` (1567-1628), `PublicPricingPage` (1733-1869), `ForgotPasswordPage` (1908-1965), `ResetPasswordPage` (1967-2043), `AcceptInvitePage` (2045-2125) + shared `FormError`/`FormSuccess` (1871-1906) ≈ 480 LOC. **DEAD-EXPORTS** (zero importers; IDE-native equivalents exist in `ide/IdeDiscovery.tsx`/`ide/IdeSystem.tsx`/`ide/IdeMore.tsx`): `InsightsPage` (28-297), `LibraryPage` (495-699), `LibraryDetailPage` (701-947), `MigrationsPage` (1055-1206), `MigrationDetailPage` (1208-1379), `EmptyProjectPage` (1631-1731), `AuditLogPage` (2128-2329), `DeploymentsPage` (2331-2536), `SpecTemplatesPage` (2538-2770), plus internal-only helpers used solely by them: `useProjectRepoPicker` (340-397), `ProjectRepoPickerBody` (399-493), `LibraryInstantiateModal` (954-1053), `MigrationDryRunModal` (1382-1493), `MigrationApplyModal` (1496-1564), `libTileBg`/`splitTags`/`creditsLabel` (301-330) ≈ **2,290 LOC dead**. |
| `MiscPages.tsx` | 1,580 | **~34% dead.** LIVE: `AdminPage` (565-650) + its 6 section components (652-1435) + `SharedPage` (1437-1580), routed at `/admin/*` and `/shared/:token` (`App.tsx:49,166,201-203`). DEAD-EXPORTS: `NotificationsPage` (24-200), `NotifRow` (202-233), `SearchPage` (244-427), `MorePage` (429-550) ≈ 530 LOC — replaced by `ide/IdeSystem.tsx`. |
| `LegalPages.tsx` | 1,065 | **~82% dead.** LIVE: `TermsPage`/`PrivacyPage`/`DpaPage` + `LegalShell` (15-196). DEAD-EXPORT: `OrgSettingsPage` (199-1032) + its private `Field`/`inputStyle` (1034-1065) ≈ 868 LOC — replaced by `ide/IdeOrg.tsx` `IdeOrgSettings`. |
| `AuthPages.tsx` | 603 | LIVE in full (`LoginPage`, `SignupPage`, `VerifyPage`, `WelcomePage` — `App.tsx:42`). |
| `AuthCallbackPage.tsx` | 124 | LIVE (`App.tsx:43`). |
| `CliLoginPage.tsx` | 151 | LIVE (`App.tsx:44`). |

### signal/components (22 files, 5,261 LOC)

| File | LOC | Verdict |
|---|---|---|
| `Modal.tsx` | 301 | LIVE — 14 importers across signal + ide. |
| `Markdown.tsx` | 59 | LIVE — SpecChat, PrReviewPanel, CodeReviewPanel, IdeRunResult, IdeProjectMore. |
| `Primitives.tsx` | 202 | LIVE — broadest-reach primitive set. |
| `Loaders.tsx` | 134 | LIVE (Spinner/LoadingState/SkeletonRow; `InlineLoader` has no external importer — dead export). |
| `Chrome.tsx` | 107 | LIVE for `TopBar`/`SectionHeader`/`IconButton`. **`TabBar` (47-69) is effectively dead** — its only consumer is the dead `MorePage` (`MiscPages.tsx:547`). |
| `Compose.tsx` | 254 | **~60% dead.** LIVE: `ScoreCard`, `PrCard` (imported only by `MiscPages.tsx:16` for SharedPage). DEAD-EXPORTS: `LiveHero`, `LiveHeroAction`, `StreamCard`, `ComposerCard`, `TaskLine` (no importers). |
| `SpecChat.tsx` | 377 | LIVE — IdeSurfaces, IdeProject. |
| `CostProgressBar.tsx` | 71 | LIVE — IdeSurfaces. |
| `PrReviewPanel.tsx` | 448 | LIVE, but **the `variant='signal'` branch (308-447, ~140 LOC) is unreachable** — the only call site is `ide/IdeProjectTabs.tsx:925-930` with `variant='ide'`. |
| `CodeReviewPanel.tsx` | 617 | LIVE, but every `variant='signal'` branch is unreachable — only call site `ide/IdeProjectMore.tsx:674-681` passes `variant='ide'`. |
| `PrCommentInbox.tsx` | 164 | LIVE, signal branch (109-163) unreachable — only `ide/IdeRunResult.tsx:1351` (`variant='ide'`). |
| `GcpCredentialPanel.tsx` | 269 | LIVE, the only `variant='signal'` call site is the **dead** `DeploymentsPage` (`ExtraPages.tsx:2397`); live consumer `ide/IdeMore.tsx:858` is `variant='ide'`. |
| `AuthLayout.tsx` | 157 | LIVE — AuthPages + ExtraPages auth pages. |
| `VerdictPill.tsx` | 60 | **Component dead; helper live.** `verdictFor` imported only by `MiscPages.tsx:17` (SharedPage). The `VerdictPill` component itself has zero importers. |
| `CodeView.tsx` | 295 | **ORPHAN** — zero importers; IDE uses its own `IdeCodeViewer` in `ide/IdeKit.tsx`. |
| `FileTree.tsx` | 191 | **ORPHAN** — zero importers (its consumer was the deleted Signal ResultPage). |
| `FileIcon.tsx` | 566 | **ORPHAN (transitive)** — only importer is the orphaned `FileTree.tsx:2`. |
| `BrandLogos.tsx` | 143 | **ORPHAN** — none of its 8 exports (`GoogleLogo`…`RazorpayLogo`) is imported anywhere; `AuthPages.tsx:17-50` re-declares its own Google/GitHub SVGs instead. |
| `UsageMeter.tsx` | 100 | **ORPHAN** — zero importers (its ComposePage consumer was deleted). |
| `UpgradeModal.tsx` | 99 | **ORPHAN** — zero importers. |
| `Glossary.tsx` | 82 | **ORPHAN** — zero importers; duplicates a SECOND orphaned dictionary in `lib/glossary.ts` (also zero importers). |
| `auth/ProtectedRoute.tsx` | 58 | LIVE — `App.tsx:2`. |

### signal/lib + hooks + icons (4 files, 512 LOC)

| File | Verdict |
|---|---|
| `lib/runUtils.ts` (244) | LIVE for `relTime`, `runTitleFor`, `repoDisplayName`, `repoIdFor`, `isLiveStatus`, `formatElapsed`. **DEAD-EXPORTS** (grep-verified zero external use): `toRunView`, `RunView`, `isTerminalStatus`, `statusToDot`, `scoreKind`, `projectIsArchived` (~95 LOC). |
| `hooks/useSignalTheme.ts` (87) | **ORPHAN** — zero importers. Consequence in §5. |
| `hooks/useProjectMeta.ts` (58) | **ORPHAN** — zero importers. |
| `icons/icons.tsx` (123) | LIVE; dead exports: `Moon`, `Sun`, `Share` (no icon import anywhere — name matches elsewhere are comments/strings). |

### signal/signal.css (2,879 LOC)

LIVE globally (`main.tsx:8`, plus re-imports in `IdeWorkspace.tsx:4`, `ConsoleShell.tsx:5`). **Roughly 1,100–1,300 lines style classnames with no TSX consumer** (grep-verified, §3). The unscoped `html, body { overflow: hidden }` at lines 109-115 is live and load-bearing for the whole app.

### console/ (3 files, 836 LOC)

All LIVE behind `localStorage es:console='1'` (`App.tsx:112,211`; toggle at `ide/IdeWorkspaces.tsx:415` / `ConsoleShell.tsx:232`). No orphans. Quality is the best of the reviewed set — but it has one serious latent styling bug (§2 BUG-1).

### design-system/ (3 files, 295 LOC)

`primitives.css` + `contrast.ts` + `contrast.test.ts` all LIVE in a narrow sense (`index.css:3` imports primitives; vitest picks up the test). But the `--c-*` layer's downstream consumers are mostly dead (§3, §5), and the test pins stale hexes (§6).

### index.css (804) + tokens.css (312)

LIVE (`main.tsx:5`, `index.css:4`). Both are majority-dead by consumer count (§3).

---

## 2. Findings

### Bugs / functional

1. **[BUG] Console chrome buttons lose their styling to the legacy Signal reset.** `signal.css:330-336` (`.signal-app button { border: 0; background: transparent; color: inherit }`, specificity 0,1,1) outranks every single-class console rule (0,1,0): `.con-newrun` (`console.css:38-51` — the top-bar AND hero "Start a new run" CTA loses its `--ide-accent` fill + accent text), `.con-runrow` (`console.css:251-263` — run rows lose border + background until `:hover`), `.con-proj-card` (`console.css:302-313`), `.con-icon`, `.con-switch`, `.con-section-link`, `.con-inline-link`. `main.tsx:35` puts `.signal-app` on `<html>`, so the reset reaches everything. This is the *exact* regression class fixed for `.ide-btn` on 2026-05-30 by scoping to `.ide-root .ide-btn` (see comment at `ide/ide.css:559-565`) — console.css (written 2026-05-27) never got the same treatment. Specificity math, not visually verified here; flag-gated so only `es:console` users see it.
2. **[BUG] Marketing-site spec handoff is dead-ended at login.** `ProtectedRoute.tsx:48-53` forwards `/?spec=…` to `/login?spec=…` and its comment (lines 12-16) claims "LoginPage reads `?spec=` on mount and stashes it" — **LoginPage does no such thing** (`AuthPages.tsx:113-151` reads only `error` and `redirect`). Likewise `AuthCallbackPage.tsx:66-67` forwards `?spec=` to `/home?spec=…`, but no live component reads `?spec=` (grep: the only `get('spec')` in the tree is AuthCallbackPage itself) and the comment's `sessionStorage['v3-anon-spec']` readers (`HomeRedirectPage`/`EmptyProjectPage`) were deleted. Net: a visitor who types a spec into the marketing hero loses it at the auth wall — a silent conversion-funnel break on both the email and OAuth paths.
3. **[BUG] Public pricing page's Monthly/Yearly toggle is fake.** `ExtraPages.tsx:1738` — `<TabsStrip items={[{label: 'Monthly'}, {label: 'Yearly · save 20%', active: true}]} />` has no `onClick`/`to` on either item; they render as `<button>`s (`Primitives.tsx:195`) that do nothing. Prices are permanently the yearly $31 figure while looking like a working toggle.
4. **[BUG] "Start trial" CTA promises a trial that doesn't exist.** `ExtraPages.tsx:1857-1861` labels the TEAM CTA "Start trial"; there is no trial machinery in `billing/` (grep for "trial" matches only accounting classes). The CTA routes to `/register` → paid subscribe.
5. **[BUG] Dark-mode CLI authorize card uses an undefined token with a white fallback.** `CliLoginPage.tsx:126` — `background: 'var(--signal-surface, #ffffff)'`; `--signal-surface` is defined **nowhere** (grep across all CSS). In dark theme the card is solid white while the `<h2>` inherits `--signal-ink` (#f3f1ed, near-white) → near-invisible heading on the device-auth consent screen. Also `CliLoginPage.tsx:144` `color: var(--signal-text)` — token also undefined (falls back to inherit by luck).
6. **[BUG] CLI authorize button is painted coral by mistake.** `CliLoginPage.tsx:133` — `background: 'var(--signal-coral, #5b8def)'`. The fallback hex is Signal **blue**; the var resolves to coral `#ff6b6b` (signal.css:40), so the primary "Authorize" action renders red. The fallback betrays the intent.
7. **[BUG-minor] `IdeRunResult`'s duplicated verdict helper drifted from the canonical one.** `ide/IdeRunResult.tsx:19-25` re-implements `verdictFor` but checks only `s === 'COMPLETE'` for the Complete label, while the canonical `signal/components/VerdictPill.tsx:40` handles `'COMPLETED' || 'COMPLETE'`. Backend status is `COMPLETED` (cf. `ide/IdeMore.tsx:101`), so a scoreless COMPLETED run reads "In progress" on the run-detail header. This is precisely the threshold/label drift `VerdictPill.tsx` was created to kill (its own header comment, lines 3-19).
8. **[BUG-minor] ConsoleHome lists plan-gate runs twice.** `ConsoleHome.tsx:66-68` — `active = runs.filter(r => !isTerminal(...))` includes `AWAITING_APPROVAL` runs which are also in `gate`; `failed` is deduped against `gate` (line 103) but `active` is not → the same run appears under "Needs your attention" and "Active runs".
9. **[BUG-minor] ConsoleHome shows archived/deleted projects.** `ConsoleHome.tsx:56-59` does not filter `archivedAt`/`deletedAt` (every other surface does, e.g. `ExtraPages.tsx:353`).
10. **[BUG-minor] PromptModal autofocuses the LAST field, not the first.** `Modal.tsx:271` puts `autoFocus` on every non-multiline input; with 2+ fields the browser focuses the last one (e.g. Admin "Grant runs" → focus lands on "Reason" instead of "Quantity", `MiscPages.tsx:904-925`).
11. **[BUG-latent] Infinite loop in the orphaned CodeView tokenizer.** `CodeView.tsx:204` — the JSON `comment` regex `/^\/\/.*$/` has no `g` flag, and `push()` (249-255) calls `re.exec` in a `while` loop; a non-global regex always returns the same match → infinite loop on any JSON line starting `//`. Dead code today, but a tab-freezing landmine if ever revived.
12. **[BUG-latent] Stale `/signal/*` routes inside live components.** `Chrome.tsx:33` (`TopBar` avatar → `/signal/account`) and `Chrome.tsx:49-53` (`TabBar` → `/signal/home`, `/signal/runs`, `/signal/compose`, **`/signal/projects/checkout-api/chat`** — a hardcoded demo-project slug, `/signal/more`). They only work because `LegacyRedirect` strips the prefix; the `checkout-api` deep link is a leftover prototype artifact. Currently unreachable (TabBar/avatar render only in dead pages) but live-exported.

### Risk

13. **[RISK] Open-redirect-shaped validation on the `redirect` param.** `AuthPages.tsx:126`, `AuthPages.tsx:239`, `AuthCallbackPage.tsx:65` all validate with `redirectParam.startsWith('/')` — `//evil.com/x` passes. React Router's `pushState` to a protocol-relative URL throws a cross-origin `SecurityError` rather than navigating, so today this is a crash/DoS edge, not an exfil, but the check should be `startsWith('/') && !startsWith('//')`. The same param is also forwarded into the backend OAuth round-trip (`AuthPages.tsx:129,169-174`), where the frontend is trusting the server-side `/cli-login`-only cookie filter to constrain it.
14. **[RISK] Raw `fetch` + raw localStorage token instead of the api client.** `MiscPages.tsx:1196-1199` (`AdminOpsSection.doReconcile` → `/api/admin/executions/reconcile`) and `CodeReviewPanel.tsx:180-182` (report download) hand-roll auth headers with `localStorage.getItem('access_token')`, bypassing `lib/api/core.ts` error parsing/refresh. (Same pattern recurs in ide/: `IdeSettings.tsx:1133`, `IdeBilling.tsx:87,549`, `IdeMore.tsx:333-387` — out of scope but the chokepoint is leaking.)
15. **[RISK] ConfirmModal swallows nothing on reject.** `Modal.tsx:171-174` — `onClick: async () => { await onConfirm(); if (!busy) onClose(); }`; if a caller's `onConfirm` throws (most catch internally, not all), it's an unhandled rejection and the modal stays open with no feedback. Also `busy` is captured at render time, so the `if (!busy)` check uses a stale value.
16. **[RISK] Legal copy is internally inconsistent / likely inaccurate** (live public pages): `LegalPages.tsx:65-67` (Privacy) claims "Data is stored in EU (**eu-west-2**) by default; US (us-east4) for customers who explicitly opt in" — eu-west-2 is an *AWS* region name and the product runs on GCP us-east4 (the trust page itself says "AES-256 (Cloud SQL)", `ExtraPages.tsx:1596`); `LegalPages.tsx:111` lists AWS as a sub-processor; `ExtraPages.tsx:1574` (Trust) says "SOC 2 Type II: **Ready**" while `LegalPages.tsx:120` (DPA) says "SOC 2 Type II controls (**audit underway**)". One of these is wrong; on legal pages that matters. Needs human/legal review.
17. **[RISK] TrustPage "Documents" rows are non-functional but look clickable.** `ExtraPages.tsx:1615-1624` — five `Row`s ("SOC 2 report", "DPA", "Sub-processors list", "Pen-test report · 2026", "Privacy policy · last updated 2026-03-14") render with `showChev` (chevron affordance) but have no `to`/`onClick`. Hardcoded dates/claims on a live trust page.

### Inconsistencies

18. **[INCONSISTENT] Two different brand marks on the login page.** Left form column: `.signal-splash .brand .logo` — `background: var(--signal-ink)` (`signal.css:1978-1989`, duplicated again at 2281-2292) → dark chip in light mode, **near-white chip in dark mode** (`--signal-ink` flips to #f3f1ed, `signal.css:67`). Right brand panel: `.auth-brand-top .brand-line .logo` — `background: var(--signal-y)` (**amber**, `signal.css:2503-2513`). Desktop login shows both "E" marks at once in different colors (the live-observed white-E vs amber-E).
19. **[INCONSISTENT] Coral (#ff6b6b — red) is the de-facto link/accent color on public pages.** `--signal-coral` is used for: every markdown/legal link (`signal.css:220-223`, `LegalPages.tsx:189`), "Forgot password?" (`AuthPages.tsx:217`), Terms/Privacy links (`AuthPages.tsx:355-360`), the `<em>` headline accent (`signal.css:1997-2000, 2524-2527`), the Spinner default (`Loaders.tsx:21`), the auth-callback spinner (`AuthCallbackPage.tsx:112`), "see all" (`signal.css:1026-1029`), live-status pills (`signal.css:1289-1300`). The same hue is simultaneously the **error/danger** color (`FormError`, failed pills, danger buttons). Brand "Signal Blue" `#5B8DEF` from the design-system layer is not even a token in signal.css — links read as errors, and live-status reads as alarm.
20. **[INCONSISTENT] Three concurrent typefaces despite a "single brand face" decree.** `tokens.css:1-16` declares JetBrains Mono "the sole brand face" and `index.css:322` sets it on `<body>`; but `signal.css:6` @imports **Space Grotesk** from Google Fonts and applies it app-wide via `.signal-app` (`signal.css:317`), and the live IDE chrome uses the **system stack** (`ide/ide.css:43` `-apple-system, …`). Console uses JetBrains (`console.css:13`). So: public pages = Space Grotesk, IDE = system-ui, console = JetBrains Mono.
21. **[INCONSISTENT] Two disconnected live theme systems.** Public/signal pages key off `data-signal-theme` (`signal.css:60`), set only once pre-paint by `main.tsx:34-46` from `localStorage('signal:theme')` — a key **no live UI ever writes** (the only writer, `useSignalTheme.ts`, is an orphan since the Shell was deleted). The IDE/console key off `data-theme` via `useUiStore`. Toggling theme in-app never changes the auth/pricing/legal pages, and a stale `signal:theme` from the retired chrome pins them forever.
22. **[INCONSISTENT] PrReviewPanel vs CodeReviewPanel error gating.** `PrReviewPanel.tsx:46-57` detects 403/429 by substring-matching the error *message* (`isGate`), while sibling `CodeReviewPanel.tsx:154,167` does it properly via `api.ApiError.status`. Same author-pattern, two mechanisms.
23. **[INCONSISTENT] ConsoleShell re-implements run labelling.** `ConsoleShell.tsx:44-46` `runLabel` = `(runTitle || userRequest || runId).slice(0,48)` — no whitespace-trim, no markdown stripping; `ConsoleHome.tsx:5` correctly uses the canonical `runTitleFor` (whose doc comment, `runUtils.ts:202-211`, records the exact whitespace-title bug this re-introduces).
24. **[INCONSISTENT] `.con-root` uses `height: 100vh`** (`console.css:10`) where the rest of the codebase deliberately migrated to `100dvh` for mobile URL-bar correctness (`index.css:16-25`, `signal.css:118`, `ide/ide.css:40`).
25. **[INCONSISTENT] `/welcome` is a public route doing authenticated work.** `App.tsx:195` mounts `WelcomePage` outside `ProtectedRoute`, but it calls `api.getGitHubAppInstallUrl()` (`AuthPages.tsx:498-510`) which requires auth; for an anonymous visitor it silently falls through to `/compose` (also protected → login bounce).
26. **[INCONSISTENT] Verified-score thresholds duplicated 4× outside the canonical helper.** `verdictFor` exists in `signal/components/VerdictPill.tsx:35-47` (canonical), then re-inlined at `ide/IdeRunResult.tsx:19-25`, `ide/IdeProjectTabs.tsx:414` (+652/666), `ide/IdeMore.tsx:41-42` (+101/115). One band change now needs 4 edits (and one already drifted — finding 7).

### Duplication

27. **[DUPLICATION] The `variant='signal' | 'ide'` dual-render hack.** Four shared components (`PrReviewPanel`, `CodeReviewPanel`, `PrCommentInbox`, `GcpCredentialPanel`) carry two full parallel JSX trees per state. Grep proves every live call site passes `'ide'` (only `variant='signal'` site is in dead `ExtraPages.tsx:2397`), so ~400 LOC of signal-branch JSX is unreachable. The ide.css token bridge (`ide/ide.css:80-119`) already remaps `--signal-*` → `--ide-*`, which is the mechanism that would have made one render path sufficient.
28. **[DUPLICATION] signal/ is also inverted onto ide/.** `PrReviewPanel.tsx:4` and `CodeReviewPanel.tsx:6` import `IdeBadge` from `@/ide/IdeKit` — the "shared lower layer" now depends on the IDE chrome it serves. `ConsoleHome`/`ConsoleShell` similarly mix `@/ide/IdeKit` + `@/signal/lib`.
29. **[DUPLICATION] Three+ syntax-highlighting stacks for one viewer.** Orphaned regex tokenizer `signal/components/CodeView.tsx` (295 LOC, builds its per-language regex table per *line* per render, `CodeView.tsx:179-243`); live tokenizer in `ide/IdeKit.tsx`; **highlight.js** in `package.json` used only for its CSS theme (`main.tsx:10`) with zero `hljs` classes emitted anywhere (grep), and **@monaco-editor/react** + 3 orphan `lib/monaco*` modules. Two icon sets too: orphaned `signal/components/FileIcon.tsx` (566 LOC) vs live `ide/IdeIcons.tsx`/`IdeKit.FileIcon`.
30. **[DUPLICATION] signal.css repeats whole blocks.** The splash brand block is defined twice nearly verbatim (`signal.css:1959-2018` vs `2275-2368`); `@keyframes signal-pulse` twice (1105, 2568); `@keyframes signal-spin` twice (264, 1855); theme-icon visibility rules twice with overlapping logic (577-594 vs 2101-2108). `AuthPages.tsx:52-79` re-implements `.oauth-btn` (signal.css:2316-2339) as inline styles — both copies exist, the CSS one dead.
31. **[DUPLICATION] Two orphaned glossary dictionaries.** `signal/components/Glossary.tsx:8-19` and `lib/glossary.ts:28+` define overlapping term sets; neither has an importer.
32. **[DUPLICATION] SpecChat repeats the reply-extraction chain 3×.** `SpecChat.tsx:108-112, 141-145, 186-190` — identical `(resp as …).reply || .message || .content` casts.

### Performance

33. **[PERF] Components defined inside a component body.** `CodeReviewPanel.tsx:237-320, 425-575` defines `MetaRow`, `SeverityCount`, `NewScanButton`, `IssueGroup`, `IssueRow`, `ErrorBanner` *inside* `CodeReviewPanel` — new component identities every render → React fully unmounts/remounts the issue list each poll tick (2.5s while scanning) and on every state change.
34. **[PERF] Google Fonts CSS @import in signal.css.** `signal.css:6` — render-blocking cross-origin @import for Space Grotesk + JetBrains Mono, *in addition to* the `<link>` JetBrains load in `index.html:32-35`. Double font pipeline, extra RTT on first paint of every page (and a GDPR-adjacent IP-to-Google disclosure the privacy page doesn't mention).
35. **[PERF] Dead-but-bundled code.** The dead page exports in `ExtraPages`/`MiscPages`/`LegalPages` are tree-shakeable in production (pure declarations), but the ~1.2K lines of dead CSS in signal.css + ~440 in index.css are **not** tree-shaken — CSS ships in full to every visitor, including the auth pages that pa11y targets.

### A11y

36. **[A11Y] Modal claims a focus trap it doesn't have.** `Modal.tsx:4-8` says "Focus-trapped"; the implementation only focuses the dialog on open (line 34). Tab walks straight out into the background page; focus is not restored to the trigger on close. Affects every confirm/prompt across both chromes (live).
37. **[A11Y] Decorative chips are buttons.** `Primitives.tsx:92-107` — `Chip` always renders `<button>` even with no `onClick` (e.g. library tag chips, `ExtraPages.tsx:668-671`): focusable, announced as buttons, do nothing.
38. **[A11Y] TabsStrip has no tab semantics.** `Primitives.tsx:182-202` — plain buttons/links, no `role=tablist/tab`, no `aria-selected`, no `aria-current`; the fake pricing toggle (finding 3) compounds it.
39. **[A11Y] FileTree keyboard support is cosmetic.** `FileTree.tsx:118-166` — nodes are `role='button'` + `tabIndex=0` with **only** `onClick`; no Enter/Space key handler, and `role='tree'` (187) contains buttons instead of `treeitem`s. (Orphan today.)
40. **[A11Y] pa11y setup is unrunnable.** `.pa11yci.json` exists and targets the public pages, but: `package.json` has **no `a11y` script** and no `pa11y-ci` devDependency; the config hardcodes a macOS Chrome path (`executablePath`); and it crawls `/plans` — a protected route since the IDE-only switch, so it would audit the login redirect. The doc-string's "npm run a11y" is fiction.
41. **[A11Y] ProtectedRoute's loading spinner is the old purple V2 styling.** `ProtectedRoute.tsx:41-42` — Tailwind `bg-[#0f1117]` + `border-purple-500`, matching neither token system; full-screen flash in neither theme's palette. Also no `role=status`/label on the spinner.

### Smells (selected)

42. **[SMELL] `AdminMiniSelect` background is an undefined-token chain.** `MiscPages.tsx:795` — `var(--signal-surface-2, var(--signal-surface))`, both undefined → invalid declaration → transparent select on the live `/admin/*` page (dark-mode `<option>` popups are then UA-styled).
43. **[SMELL] AuditLogPage CSV export doesn't escape fields** (`ExtraPages.tsx:2199-2221`) — commas in `actorEmail`/`targetId` shear columns; values starting `=` are an Excel formula-injection footgun. (Dead page, but the same pattern is one paste away from a live page.)
44. **[SMELL] Dead NotificationsPage navigates by synthetic `PopStateEvent`** (`MiscPages.tsx:186-191`) instead of `useNavigate` — fragile pattern preserved in tree.
45. **[SMELL] `applyAsSpec` silently degrades.** `SpecChat.tsx:199-207` — on finalize failure it applies the last assistant message *without telling the user* the finalize step failed.
46. **[SMELL] Hardcoded hexes sprinkled in live TSX/CSS:** `#5443a8` (lavender-text stand-in) at `ExtraPages.tsx:1099,1107,2620`, `MiscPages.tsx:1503,1511`, `LegalPages.tsx:550,637,706,870`, `signal.css:1490` — because the token `--signal-lavender-text` is referenced once with a fallback (`signal.css:541`) but never defined; `#1d6543` (`AuthPages.tsx:426`); `#16a34a`/`#dc2626` (`CliLoginPage.tsx:84,131`); `#f1c945` (`signal.css:1431`); `#5bc196` (`signal.css:1557`).
47. **[SMELL] Stale comments mislead:** `AuthCallbackPage.tsx:48-60` documents a marketing-stash flow whose readers are deleted; `ProtectedRoute.tsx:12-16` documents behavior LoginPage doesn't have; `useUiStore.ts:33-37` claims `uiMode` is kept for "the undeleted Signal AccountPage" (deleted); `primitives.css:5-8` says it feeds `v3/v3.css` (deleted) and `website/index.html`.

---

## 3. Dead code & junk — grep-verified

### Whole-file orphans (zero importers in frontend/src)

| File | LOC |
|---|---|
| `signal/components/FileIcon.tsx` | 566 (transitive via FileTree) |
| `signal/components/CodeView.tsx` | 295 |
| `signal/components/FileTree.tsx` | 191 |
| `signal/components/BrandLogos.tsx` | 143 |
| `signal/components/UsageMeter.tsx` | 100 |
| `signal/components/UpgradeModal.tsx` | 99 |
| `signal/hooks/useSignalTheme.ts` | 87 |
| `signal/components/Glossary.tsx` | 82 |
| `signal/hooks/useProjectMeta.ts` | 58 |
| **Subtotal** | **1,621** |

(Outside the strict scope but proven by the same grep: `lib/glossary.ts`, `lib/verdict.ts`, `lib/analytics.ts` (= posthog-js init that `main.tsx` never imports), `lib/monacoSetup.ts`, `lib/monacoErrorPatterns.ts`, `lib/monacoGeistTheme.ts`, `lib/techDetect.ts`, `lib/constants.ts`, `lib/billing-api.ts`, `lib/invoice-api.ts` are all importer-less.)

### Dead exports inside live files

- `ExtraPages.tsx` ≈ 2,290 LOC (9 pages + 5 modals/helpers — see §1).
- `LegalPages.tsx` ≈ 868 LOC (`OrgSettingsPage` + `Field` + `inputStyle`). Note: the stale "seat pool (**min 2**)" copy at `LegalPages.tsx:514` — contradicting both its own `min={1}` input (line 521-524) and the 1-seat backend — lives only here, in dead code. The live `ide/IdeOrg.tsx` has no such copy (grep).
- `MiscPages.tsx` ≈ 530 LOC (`NotificationsPage`, `NotifRow`, `SearchPage`, `MorePage`).
- `Compose.tsx` ≈ 154 LOC (`LiveHero`, `LiveHeroAction`, `StreamCard`, `ComposerCard`, `TaskLine`).
- `Chrome.tsx` `TabBar` ≈ 28 LOC; `VerdictPill` component ≈ 20; `Loaders.InlineLoader` ≈ 16; `runUtils` 6 exports ≈ 95; `icons` `Moon`/`Sun`/`Share` ≈ 30.
- The unreachable `variant='signal'` branches ≈ 400 LOC across 4 components (§2-27).

**signal/ total: ≈ 6,050 of ~12,690 TSX/TS LOC dead (~48%), plus ~1,100–1,300 of signal.css's 2,879.**

### Dead CSS (classname has zero TSX consumers — grep over frontend/src)

- `signal.css`: the entire deleted-Shell layout system — `.signal-sidebar`, `.sidebar-head/-brand/-close/-nav/-foot/-theme`, `.logo-chip(-sm)`, `.nav-group/-link/-icon/-badge`, `.mobile-bar/-menu-btn/-brand/-theme/-tabs`, `.theme-moon/-sun/-lbl-*` (lines ~347-727); `.signal-top-back/-theme` (961-1012); `.signal-toast` (2084-2099); `.signal-plans-grid` (294-312); `.signal-rail-*` (2020-2082); `.oauth-btn`, `.or-row` (2316-2357); `.auth-preview-card` + `signal-bar` keyframes (2540-2636); plus CSS whose only consumers are dead components: `.signal-tabbar/.signal-tab` (TabBar), `.signal-live-hero`, `.signal-stream-card`, `.signal-task-line`, `.signal-composer-card`, `.signal-split-runs`, `.signal-file-tree*`, `.signal-glossary*`, `.signal-notif*`. The heavily-commented 5-layer scroll-fix block (92-145) targets `.signal-shell/.signal-main/.signal-content` — **none of those classes exist in any TSX** — yet its unscoped `html, body { overflow: hidden }` (109-115) still governs the live app.
- `index.css`: `.glass-panel`, `.terminal-log`, `.gradient-mesh`, `.es-card(-elevated)`, all six `.es-chip--*`, `.surface-card`, `.press-effect`, `.hover-lift`, `.tap-fade`, `.hit-target`, `.reveal-on-mount`, `.segmented-tab-indicator`, `.compact-tree-indent`, `.chat-bubble-content`, `.responsive-table` (both blocks, 253-317 **and** 348-456 — the rule was pasted twice in slightly different forms), `.page-gutter`, `.sticky-bottom-bar`, `.safe-area-*`, `.h-screen-dvh` family, `.scrollbar-hide`, `.line-clamp-*`, `.shimmer`, and every `.v3-root`/`.v3-eyebrow`/`.v3-statk`/`.v3-chip`/`--v3-fs-*` rule (the v3 tree was deleted). **Every one of these returned zero TSX consumers.** What's genuinely live in index.css: the element-level rules (html/body height, body font, form font inherit, scrollbar, focus-visible ring, iOS input zoom guard, 44px/40px touch-target floors, touch `:active` feedback, prefers-reduced-motion, the `text-[npx]` mobile bumps that hit Tailwind arbitrary classes still used in ide/).
- `tokens.css` (312) + `design-system/primitives.css` (120): a third token system whose vars are consumed **only** by index.css (mostly its dead utilities) — live reach is body bg/text, scrollbar grays, and the focus ring. No component in signal/, ide/, or console/ references `--geist-*`, `--gray-*`, `--accent-blue`, `--surface-*`, `--c-*` (grep). `--brand-grad`, `--surface-page`, the motion/radius/spacing/breakpoint scales, `--hit-*`: zero consumers anywhere.

### Provably unused npm dependencies (grep over all of frontend/src for import/require)

| Package | Evidence |
|---|---|
| `d3` + `@types/d3` | zero imports |
| `recharts` | zero imports |
| `highlight.js` | only `main.tsx:10` imports its **CSS theme**; zero `hljs` classes are produced anywhere → the stylesheet styles nothing |
| `@monaco-editor/react` | imported only by `lib/monacoSetup.ts`, which itself has zero importers |
| `qrcode.react` | zero imports |
| `cmdk` | zero imports (IdeKit has its own palette) |
| `framer-motion` | zero imports |
| `class-variance-authority` | zero imports |
| `@radix-ui/react-tabs` | zero imports |
| `lucide-react` | zero imports (`signal/icons/icons.tsx:1` even says "so we don't add a dep" — but the dep is in package.json anyway) |
| `posthog-js` | imported only by orphaned `lib/analytics.ts` |

Live deps confirmed: react/-dom, react-router-dom, react-markdown + remark-gfm, zustand, clsx + tailwind-merge (via `lib/utils.ts` `errMsg`/`cn`), @opentelemetry/* (via `lib/telemetry.ts` ← `main.tsx:12`).

### Misc junk

- `main.tsx:10` — the highlight.js CSS import ships a dead stylesheet on every page load with a comment claiming it powers "the IDE file viewer" (it doesn't; IdeKit emits its own classes).
- `ExtraPages.tsx:1-3` header comment still advertises the file as the home of Insights/Library/Migrations/Audit/Deployments/Templates — all dead here, live in ide/.
- Fixed SVG gradient ids that would collide if instantiated twice: `FileIcon.tsx:61` (`kt-grad`), `BrandLogos.tsx:76` (`jira-a`) — both orphans.

---

## 4. Provider / model / tier leakage into UI

- **Tier names hardcoded across live signal surfaces:** `ExtraPages.tsx:1741-1781` (FREE/TEAM/ENTERPRISE cards + feature bullets), `MiscPages.tsx:772-776` (`ADMIN_USER_TIERS`/`ADMIN_ORG_TIERS` arrays incl. hidden `PRO`), `ExtraPages.tsx:2153-2163` (tier-conditional audit-log error copy: "TEAM+ feature"), `LegalPages.tsx:424` (`isEnterprise` gate for the OpenRouter row — dead page, but the same gate is live in IdeOrg).
- **Provider names in UI:** `LegalPages.tsx:430-435` (Anthropic/OpenAI/Gemini/OpenRouter key rows — dead, mirrored live in ide/IdeOrg); `ExtraPages.tsx:1620` Trust page hardcodes the sub-processor list "GCP · Anthropic · OpenAI · Google AI"; `LegalPages.tsx:70-72` privacy hardcodes "Anthropic, OpenAI, and Google". These will silently rot when the provider set changes (DeepSeek/OpenRouter already exist server-side and appear in neither legal/trust list).
- **Pricing numbers vs backend truth:**
  - "15 runs / month" (`ExtraPages.tsx:1747`; also `AuthPages.tsx:286` signup lede) — **matches** the canonical DB value (`V200__bump_free_tier_to_15_runs.sql` sets `tier_config.monthly_run_limit = 15`), but `application.yml:638-641` still says `app.tiers.free.monthly-run-limit: 5` — a backend config-drift trap two sources apart.
  - "TEAM $31/seat/mo" shown unconditionally as the *yearly* figure behind the fake toggle (finding 3); backend list price is $39 monthly.
  - "FREE $3 hard cost cap" and "TEAM $25 hard cap" (`ExtraPages.tsx:1749,1762`) — no matching config key exists in `application.yml` (grep `cost-cap|hard-cap|platform-cap`); unverifiable, and at odds with the documented $8 default platform cap. "500 credits / seat / month" (`1761`, repeated in dead `UpgradeModal.tsx:43`) likewise has no config anchor.
  - Run-credit math leak: `ExtraPages.tsx:1748` "1-credit specs only" hardcodes the entitlement rule.
- **Status-enum leakage:** `runUtils.ts:26-43` and `ConsoleHome.tsx:15-17` each hardcode their own status vocabularies (`COMPLETE` vs `COMPLETED` both appear — see finding 7); `CodeReviewPanel.tsx:39-46` hardcodes the scan-status enum with display labels.
- Dead-code-only leakage worth deleting rather than fixing: `EmptyProjectPage` "28 readymade solutions … 11 migrations" (`ExtraPages.tsx:1721`), `MorePage` "ExecuteSpec · v1.0.0-M1" literal (`MiscPages.tsx:544`), AdminTopups INR-first "₹{amountInr} / ${amountUsd}" (`MiscPages.tsx:1367` — this one is live, operator-only).

---

## 5. Styling / theme

- **Four token layers coexist:** (1) `--c-*` primitives + `--geist/--gray/--accent/--surface/--status-*` semantics (`design-system/primitives.css` + `tokens.css`, keyed on `data-theme`) — effectively vestigial (§3); (2) `--signal-*` (`signal.css`, keyed on `data-signal-theme`) — public pages; (3) `--ide-*` (`ide/ide.css`, keyed on `data-theme`) — IDE + console; (4) the `--signal-*→--ide-*` bridge under `.ide-root` (`ide/ide.css:87-119`). Console adds no tokens (good).
- **The two theme attributes are driven by two unconnected mechanisms** (finding 21): `data-signal-theme` is write-once at boot (`main.tsx:34-46`); `data-theme` is live via useUiStore. The only component that could change the former (`useSignalTheme`) is an orphan. Public-page dark/light is therefore frozen per-visit and can disagree with the in-app theme.
- **Undefined tokens referenced:** `--signal-surface` (3 sites), `--signal-surface-2` (1), `--signal-text` (1), `--signal-lavender-text` (1 + 9 hardcoded `#5443a8` stand-ins). `--signal-mint-text` exists but equals `#0e0f12` (dark ink) — `UpgradeModal.tsx:84` uses it as a green checkmark color, which renders near-black on light (dead code).
- **The global reset is the biggest hazard:** `signal.css:330-336` `.signal-app button {…}` already caused the documented `.ide-btn` incident and currently un-styles the console chrome (finding 1). Any future single-class button styling anywhere in the app silently loses to it.
- **Contrast notes (live pages):** coral-on-card `.signal-row .trailing.fail { color: var(--signal-coral) }` (`signal.css:1161-1163`) — #ff6b6b on #ffffff is ≈ 2.9:1, below AA-body, used for "fail" trailing text; `--signal-y` (#f4d35e) on dark hero (`signal.css:1342-1351`) is decorative-OK but used for 11px stat labels. The design-system layer has carefully audited AA math (`primitives.css` F-15/16/17 comments) — none of which applies to the signal palette actually shown to anonymous users, which has never had an equivalent audit.
- **Duplication:** see findings 30, 34; plus `index.css` carries two near-identical `responsive-table` implementations (253-317, 348-456), both dead.
- `signal.css:6` @import of Space Grotesk contradicts the JetBrains-only typography decision recorded at `tokens.css:1-16` (finding 20).

---

## 6. Test coverage

- **Exactly one test file exists in the frontend:** `design-system/contrast.test.ts` (vitest is wired — `vitest.config.ts`, `npm test`).
- That single test is **pinning stale values**: `contrast.test.ts:21-22` fixes `GRAPHITE_2 = '#14171C'`, `GRAPHITE_3 = '#1B1F26'`, and `graphite5 = '#333B46'` (line 33), but `primitives.css:34-39` re-warmed the ramp on 2026-05-06 to `#1a1f29` / `#232936` / `#3d4553`. The file's own contract ("If a future token tweak shifts a hex, update BOTH", lines 12-14) was violated; the test now verifies ratios against surfaces that no longer exist, so it cannot catch a real regression on the live palette.
- Zero tests for anything reviewed here: no tests for `verdictFor` (which has demonstrably drifted across copies), `runUtilities` (`repoDisplayName`'s documented bug-fix ladder is untested), `Modal` focus behavior, redirect-param validation, ConsoleHome partitioning, or the public pages.
- The a11y harness is non-functional (finding 40): config present, runner absent, one target URL now auth-walled.

## 7. Security notes (parked)

- Access token in `localStorage` (`AuthCallbackPage.tsx:38`, `useAuthStore.ts:30-37`) — XSS-exfiltratable by design; refresh token correctly kept HttpOnly (`AuthCallbackPage.tsx:28-40`).
- `CliLoginPage.tsx:54-60` — PAT is passed via URL query to `127.0.0.1:<port>/cb` / `vscode://` URI: lands in browser history and any localhost listener on that port; `state` echo is the only binding; every Authorize mints a new never-expiring-by-default PAT named "CLI · <date>".
- `redirect` param validation permits `//host` (finding 13).
- Raw-fetch sites with hand-rolled bearer headers (finding 14).
- Admin actions (halt-all, reconcile, tier/role/status mutation) are gated client-side only by `selectIsSuperAdmin` (`MiscPages.tsx:572-580`) — fine *iff* the server enforces (it does per SecurityConfig docs), but the page renders the full UI before the redirect effect runs on a slow store hydration.
- CSV export without quoting/formula-escaping (finding 43, dead).
- `LegalShell` injects an element-scoped `<style>` block (`LegalPages.tsx:186-190`) — selector `article a` leaks to any other article on the page (none today).
