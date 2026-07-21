# ExecuteSpec Enterprise — Official Identity Asset Manifest

Status: **Read-only source and licensing review; assets not yet imported**  
Reviewed: 2026-07-20  
Figma file: `aq1I0rCCUpfRKe7gyyO41S`

## Non-negotiable display contract

1. Every third-party integration row shows an official provider/product mark and canonical name when published terms permit its use.
2. Every model row shows the serving provider name and full registry display label. It shows the official provider mark when approved. DeepSeek, OpenRouter and Fireworks AI deliberately omit the mark until permission is secured. Model publisher and serving route must not be conflated.
3. Identity and operational status are separate visual fields. A logo never communicates health, permission, availability or selection.
4. Marks are never recoloured, redrawn, stretched, cropped, placed on an unapproved background or used to imply endorsement.
5. When published terms do not permit product-UI logo use, preserve the provider and omit the logo. Do not insert a fabricated or neutral substitute for a named commercial provider. A neutral mark remains valid only for customer-hosted/unbranded endpoints.

User decision, 2026-07-20: DeepSeek, OpenRouter and Fireworks AI stay available and named in the product without logos while Navneet seeks permission. This is an explicit exception to the earlier all-provider-logo rule, not an accidental omission.

## Current Figma evidence

| Identity | Representative node | Current evidence | Disposition |
|---|---|---|---|
| Bitbucket | `23:51` | named 14×14 frame with vector mark | retain provisionally; replace/verify against current Atlassian asset |
| Jira | `23:58` | named 14×14 frame with vector mark | retain provisionally; replace/verify against current Atlassian asset |
| Slack | `23:65` | named 14×14 frame with four vectors | retain provisionally; verify colour, clear space and dark-mode treatment |
| OpenAI | `31:201` | named 14×14 frame with seven vectors | verify against downloaded current asset and usage terms |
| Anthropic | `31:209` | named 14×14 frame with vector mark | verify against current press-kit asset |
| Google/Gemini | `31:211` | named 14×14 frame with vector mark | use provider identity `Google`; verify specific approved asset/background |

A vector-shaped layer is not sufficient evidence of provenance or licence. The final asset record must include source URL, downloaded asset filename/version/date, permitted use, dark/light variant, clear-space rule and reviewer.

## P0 integration identity manifest

| Canonical UI name | Product maturity | Current Figma asset | Official source | Import decision |
|---|---|---|---|---|
| GitHub | P0 | missing | [GitHub Brand Toolkit](https://brand.github.com/) and [logo policy](https://docs.github.com/en/site-policy/other-site-policies/github-logo-policy) | download approved mark; preserve proportions and policy |
| Bitbucket Cloud | P0 | provisional | [Atlassian logos](https://atlassian.design/foundations/logos) and [trademark policy](https://www.atlassian.com/legal/trademark) | use unmodified official Bitbucket product logo plus name |
| Jira Cloud | P0 | provisional | [Atlassian logos](https://atlassian.design/foundations/logos) and [trademark policy](https://www.atlassian.com/legal/trademark) | use unmodified official Jira product logo plus name |
| Confluence Cloud | P0 | missing | [Atlassian logos](https://atlassian.design/foundations/logos) and [trademark policy](https://www.atlassian.com/legal/trademark) | import official product logo plus name |
| Figma | P0 | missing | [Figma brand guidelines and downloads](https://www.figma.com/using-the-figma-brand/) | import approved mark without modification; ExecuteSpec remains more prominent |
| FigJam | P0 within Figma connector | missing | [Figma brand guidelines and downloads](https://www.figma.com/using-the-figma-brand/) | use approved FigJam/product identity if supplied; otherwise Figma mark + `FigJam` text subject to guidelines |
| Slack | P0 notification/deep link; P1 query/actions | provisional | [Slack Media Kit](https://slack.com/media-kit) | replace/verify from supplied kit; preserve colours and clear space |
| GitHub Actions/Checks | P0 | missing | GitHub toolkit/policy above | use GitHub provider mark + canonical `GitHub Actions` label unless an approved product-specific asset is supplied |
| Generic CI | P0 neutral protocol | not applicable | ExecuteSpec-owned neutral identity | use neutral `CI` mark; never imply a vendor |
| Preview/deployment route | P0 declared supported route | provider-dependent | applicable cloud/provider official source | render exact serving provider only after route resolution |

## P1/Conditional and enterprise-anchor identity backlog

These assets are not required to close the first P0 prototype but the component API must support them without redesign.

- Incident/ITSM: PagerDuty, ServiceNow, incident.io, FireHydrant.
- SCM/work: GitLab, Azure Repos, Azure Boards, Linear, Asana, monday.com, ClickUp.
- Knowledge: Microsoft SharePoint/OneDrive, Google Drive/Docs, Notion, Box.
- Communication: Microsoft Teams.
- Runtime: AWS, Microsoft Azure, Google Cloud, Kubernetes.
- Delivery: GitLab CI, Jenkins, Azure Pipelines, Argo CD, Terraform Cloud, Bitbucket Pipelines.
- Observability: Datadog, Splunk, Dynatrace, New Relic, Grafana/Prometheus, Elastic, Sentry.
- Identity: Microsoft Entra ID, Okta, Google Workspace.
- Security/quality: SonarQube, Snyk, GitHub Advanced Security, Wiz, Veracode, Checkmarx.
- Artifacts/secrets: JFrog, Nexus, cloud registries, Vault and cloud secret managers.

Every backlog asset remains `Planned`, `P1` or `Conditional` in Figma. Presence of a logo must not imply a working connector.

## Model-provider identity manifest

| Provider value | Canonical provider name | Current Figma asset | Official/legal evidence | Release decision |
|---|---|---|---|---|
| `anthropic` | Anthropic | provisional | [Anthropic newsroom press kit](https://www.anthropic.com/news) | obtain current kit asset; legal/brand review required before production |
| `openai` | OpenAI | provisional | [OpenAI design guidelines](https://openai.com/brand/) | use exact supplied logo; do not recolour or imply endorsement |
| `gemini` | Google | provisional | [Google Brand Resource Center](https://about.google/brand-resource-center/guidance/) | show approved Google provider identity plus full Gemini model label; no invented Gemini mark |
| `deepseek` | DeepSeek | deliberately omitted | [DeepSeek terms](https://cdn.deepseek.com/policies/en-US/deepseek-terms-of-use.html) restrict logo use without permission | keep provider and full label; omit logo pending permission |
| `openrouter` | OpenRouter | deliberately omitted | no official permissive brand-kit evidence found in this review | keep provider and full label; omit logo pending permission |
| `fireworks` | Fireworks AI | deliberately omitted | [official product site](https://fireworks.ai/) provides identity but no verified third-party usage licence found | keep provider and full label; omit logo pending permission |
| customer-hosted/unbranded | Customer-hosted | missing | customer-owned | use neutral ExecuteSpec endpoint mark plus customer-supplied name; never borrow a model publisher logo |

OpenAI's published guidance requires exact supplied assets, prescribed clear space and accurate model references. Google prohibits brand use that implies endorsement. DeepSeek's published terms explicitly restrict logo use without permission. These are product-release constraints, not optional design polish.

## Current model label contract

The live `application.yml` registry currently contains 38 display-label rows across Anthropic, OpenAI, Google/Gemini, DeepSeek, OpenRouter and Fireworks routes. Figma must not hard-code a shortened picker catalogue. `Model/Identity` accepts:

- `providerId` — serving provider enum;
- `providerName` — canonical provider display name;
- `providerLogo` — approved asset instance or absent only for the three recorded permission-pending providers;
- `modelId` — exact registry key;
- `modelLabel` — full `display-label` from the API;
- `routeLabel` — Native, OpenRouter, Fireworks or customer-hosted where applicable;
- `availability`, `policy`, `maturity` and `health` — separate fields;
- `logoApprovalState` — approved, provisional, permission-pending-omitted or customer-supplied.

Examples that must remain distinguishable:

- `DeepSeek V4 Pro (Native)` served by DeepSeek;
- `DeepSeek V4 Pro (OpenRouter)` served by OpenRouter;
- `DeepSeek V4 Pro (Fireworks)` served by Fireworks AI.

The model publisher name inside the label does not change which provider logo is shown.

## Figma asset-component construction

After permission and Phase 1 approval:

1. create one local component per approved identity asset, named `Identity/<Canonical name>`;
2. retain original vector geometry and approved colours; create dark/light variants only when supplied by the provider;
3. preserve a 24px icon slot with provider-defined internal clear space;
4. expose identity via `INSTANCE_SWAP` in Source/Identity and Model/Identity;
5. show canonical text beside the mark in expanded states; collapsed states require accessible name and tooltip;
6. annotate source URL, retrieval date, licence/terms status and reviewer in component description/shared data;
7. never detach or redraw the identity to fit the ExecuteSpec palette.

## Asset acceptance checklist

- downloaded from an official vendor-controlled source;
- source URL and retrieval date recorded;
- permitted UI/integration-identification use verified;
- no colour/proportion/clear-space modification;
- dark/light/background rules verified at 16, 20 and 24px;
- name and mark remain readable at 200% zoom and in high contrast;
- accessible name uses the canonical provider/product name;
- maturity and connector health remain separate;
- provider identity plus full model label verified for every API-returned model row;
- legal/brand reviewer recorded for permission-required providers.

## Resolved authority decision

DeepSeek, OpenRouter and Fireworks AI remain named and selectable, with full model labels, but without logos until written permission and approved files exist. No fabricated or neutral substitute is used. When permission is secured, the same `Model/Identity` component accepts the approved asset without changing the provider/model information architecture.
