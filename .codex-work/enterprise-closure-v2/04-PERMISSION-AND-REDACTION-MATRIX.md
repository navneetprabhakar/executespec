# ExecuteSpec Enterprise — Permission, Redaction, and Inherited-Shell Matrix

Status: **Normative trust contract**

## Core rule

Authorisation is evaluated before retrieval, graph expansion, prompt input, answer composition, caching, inherited-shell rendering, and prototype continuity. A hidden object cannot leak through a title, count, avatar, graph edge, timestamp, provider row, history entry, or disabled action.

## State matrix

| State | Organisation/team | Objective/project | People | Sources/repos | Graph/relationships | Freshness/history | Permitted actions |
|---|---|---|---|---|---|---|---|
| Zero-information workspace | Current organisation/workspace name only if user created/owns it | Approved declared Objective/project only; otherwise empty | Current user and explicitly declared invitees only | None until connected; connector catalogue may show provider names without tenant data | Cold-start declared spine only | `No evidence connected`; no fake timestamps | complete brief, review proposals, connect source |
| No result | Authorised current scope | Query subject may show if authorised | Only already-authorised visible people | Searched source categories and counts may show; no inaccessible object names | No inferred hidden relationship | search time and scopes searched | refine query, change permitted scope |
| Not searched | Authorised current scope | Authorised current object | None newly revealed | Name only the source category intentionally excluded | None | reason not searched | include source if user has permission |
| Source unavailable/timeout | Authorised current scope | Previously authorised cached object may show as stale with observation time | Previously authorised identities only | Source name and operational error; no new content | Cached relationships marked stale | last successful observation + failure time | retry, use partial result, hand off |
| Permission denied | Neutral shell or minimum authorised scope | Withhold object title/content if the viewer lacks object permission | Withhold names, avatars and counts | Source category may show only when needed to explain denial; no repo/page/channel identifiers | No hidden nodes, edges, counts or inferred existence | no cached content; may show access-check time | request access, switch account/scope, return |
| Permission revoked after prior access | Current authorised shell only | Redact revoked object and derived summaries | Remove identities known only through revoked source | Name source connection only if admin/user may know it | Purge/disable derived traversal | show revocation time, not old content | re-authorise, delete derived cache, return |
| Expired deep link | Neutral view-choice shell | Withhold title/content unless the base object remains independently authorised | Withhold source-derived participants | May identify originating product and expired link type, not target identifiers | None | expiry time | sign in, request new link, return to safe destination |
| Wrong product view | Neutral Studio/Enterprise selector | No opposite-view content | None | No connected-source detail | None | None | open correct view after re-authorisation |
| Shared result/evidence | Recipient's recomputed scope | Only independently authorised subject fields | Only independently authorised people | Citations filtered per recipient | Relationships recomputed; never inherit creator access | current viewer-specific freshness | request access, view redacted summary, return |
| Deleted/disconnected source | Authorised shell | References become tombstone/removed-source indicators | No source-only identities | Connector name/status may show to admin; content absent | Derived nodes/edges removed or tombstoned per policy | deletion/disconnect audit time | verify purge, reconnect as a new authority event |

## Inherited-shell rules for audited blockers

- **E01Z:** remove populated Objective rows, source rows, people, conflicts and Work Graph. Keep only the current user-created workspace, declared brief progress and connector catalogue.
- **E02D:** replace Inspect with denial explanation and requested scope. Do not show source IDs, repository names or populated relationships from the denied query.
- **X01A:** use a neutral selector outside both product shells. No Enterprise organisation/project/objective context renders before selection.
- **X02B:** use a neutral expired/denied deep-link shell. Withhold object title, source identifiers, member names, repo/Jira/Slack details and graph content.
- **E09P:** after disconnect or during purge, connector health is `Disconnected` or `Deletion in progress`; cached facts cannot display as healthy/current.

## Redaction behavior

- Redaction placeholders describe class, not hidden value: `Repository withheld`, not `checkout-api (no access)`.
- Counts are sensitive when they reveal hidden existence; use `Some results were omitted` rather than an exact count unless authorised.
- Avatars, initials, ownership hints and activity timestamps are content.
- Tooltips and accessibility names follow the same redaction as visible text.
- Analytics contain stable opaque IDs and reason codes, never leaked titles/content.
- Screenshots, exports, notifications and deep-link previews run the same redaction policy.

## Acceptance gates

1. Controlled cross-tenant and source-ACL tests find zero unauthorised facts in candidates, prompts, rendered UI, cache, trace, export, notification and accessibility tree.
2. Revocation removes derived visibility without erasing immutable audit required by policy.
3. No neutral/denied state inherits populated panes from the previous screen.
4. `No result`, `not searched`, `permission denied`, and `source unavailable` have distinct copy, icon, recovery and analytics reason.
5. Shared/deep-linked content re-authorises for the recipient and handles expiry/source-version change safely.
