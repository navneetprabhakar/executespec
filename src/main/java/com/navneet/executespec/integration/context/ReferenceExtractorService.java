package com.navneet.executespec.integration.context;

import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.repository.OrgRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans a run's user request for JIRA issue keys and Confluence page URLs,
 * fetches the referenced content using org-level credentials, and returns
 * an enriched request string with the extracted context appended.
 *
 * <p>If the user vaguely mentions "jira ticket" or "confluence page" without
 * a specific reference, returns a clarification signal so the pipeline can
 * pause and ask the user.
 *
 * <p>All fetching is <b>non-fatal</b> — failures log a warning and the
 * original request is returned unchanged.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ReferenceExtractorService {

    // ── Patterns ─────────────────────────────────────────────────────────────

    /**
     * Matches Jira issue keys like PROJ-123, ABC-45, MYTEAM-9999.
     * Must be preceded by whitespace/start or a slash (URL) to avoid false positives.
     */
    private static final Pattern JIRA_KEY_PATTERN =
            Pattern.compile("(?:^|[\\s/\\[(])([A-Z][A-Z0-9]{1,9}-\\d{1,6})(?=[\\s)\\].,;:!?]|$)");

    /**
     * Matches Atlassian Jira browse URLs:
     *   https://example.atlassian.net/browse/PROJ-123
     */
    private static final Pattern JIRA_URL_PATTERN =
            Pattern.compile("https?://[^\\s]+/browse/([A-Z][A-Z0-9]{1,9}-\\d{1,6})");

    /**
     * Matches Confluence page URLs with numeric page ID:
     *   https://example.atlassian.net/wiki/spaces/ENG/pages/12345/Page+Title
     *   https://example.atlassian.net/wiki/pages/viewpage.action?pageId=12345
     */
    private static final Pattern CONFLUENCE_PAGE_URL_PATTERN =
            Pattern.compile("https?://[^\\s]+/wiki/(?:spaces/[^/]+/pages/(\\d+)|pages/viewpage\\.action\\?pageId=(\\d+))");

    /**
     * Vague Jira mentions — "jira ticket", "jira issue", etc. WITHOUT a specific key.
     */
    private static final Pattern VAGUE_JIRA_MENTION =
            Pattern.compile("(?i)\\b(?:jira\\s+(?:ticket|issue|story|bug|task|epic)|refer(?:ence)?\\s+(?:to\\s+)?(?:the\\s+)?jira)\\b");

    /**
     * Vague Confluence mentions — "confluence page", "confluence doc", etc. WITHOUT a URL.
     */
    private static final Pattern VAGUE_CONFLUENCE_MENTION =
            Pattern.compile("(?i)\\b(?:confluence\\s+(?:page|doc|document|article|spec)|refer(?:ence)?\\s+(?:to\\s+)?(?:the\\s+)?confluence)\\b");

    private final OrgRepository orgRepository;
    private final RestTemplate integrationRestTemplate;

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Scans the user request for JIRA/Confluence references and enriches
     * the request with fetched content.
     *
     * @param userRequest the original user request text
     * @param orgId       the organization ID (for credential lookup)
     * @return an {@link ExtractionResult} — enriched, unchanged, or needs clarification
     */
    public ExtractionResult extractAndEnrich(String userRequest, String orgId) {
        if (userRequest == null || userRequest.isBlank() || orgId == null) {
            return ExtractionResult.unchanged(userRequest);
        }

        // Detect concrete references
        Set<String> jiraKeys = extractJiraKeys(userRequest);
        Set<String> confluencePageIds = extractConfluencePageIds(userRequest);

        boolean hasConcreteRefs = !jiraKeys.isEmpty() || !confluencePageIds.isEmpty();

        // If no concrete refs, check for vague mentions
        if (!hasConcreteRefs) {
            return checkForVagueMentions(userRequest);
        }

        // Look up org credentials
        Org org = orgRepository.findById(orgId).orElse(null);
        if (org == null) {
            log.debug("[RefExtractor] Org {} not found — skipping extraction", orgId);
            return ExtractionResult.unchanged(userRequest);
        }

        List<ExtractionResult.ExtractedRef> extractedRefs = new ArrayList<>();
        StringBuilder contextBlock = new StringBuilder();

        // Fetch Jira issues
        if (!jiraKeys.isEmpty() && hasJiraCredentials(org)) {
            for (String key : jiraKeys) {
                try {
                    String context = fetchJiraIssue(key, org);
                    if (context != null) {
                        contextBlock.append("\n\n").append(context);
                        extractedRefs.add(new ExtractionResult.ExtractedRef("JIRA", key, key));
                        log.info("[RefExtractor] Extracted JIRA issue {} for org {}", key, orgId);
                    }
                } catch (Exception e) {
                    log.warn("[RefExtractor] Failed to fetch JIRA issue {}: {}", key, e.getMessage());
                }
            }
        } else if (!jiraKeys.isEmpty()) {
            log.debug("[RefExtractor] JIRA keys found but org {} has no JIRA credentials configured", orgId);
        }

        // Fetch Confluence pages
        if (!confluencePageIds.isEmpty() && hasConfluenceCredentials(org)) {
            for (String pageId : confluencePageIds) {
                try {
                    String context = fetchConfluencePage(pageId, org);
                    if (context != null) {
                        contextBlock.append("\n\n").append(context);
                        extractedRefs.add(new ExtractionResult.ExtractedRef("CONFLUENCE", pageId, "Page " + pageId));
                        log.info("[RefExtractor] Extracted Confluence page {} for org {}", pageId, orgId);
                    }
                } catch (Exception e) {
                    log.warn("[RefExtractor] Failed to fetch Confluence page {}: {}", pageId, e.getMessage());
                }
            }
        } else if (!confluencePageIds.isEmpty()) {
            log.debug("[RefExtractor] Confluence pages found but org {} has no Confluence credentials configured", orgId);
        }

        if (extractedRefs.isEmpty()) {
            return ExtractionResult.unchanged(userRequest);
        }

        // Build enriched request: original text + extracted context
        String enriched = userRequest + "\n\n---\n## Extracted Reference Context"
                + contextBlock;

        return ExtractionResult.enriched(enriched, extractedRefs);
    }

    /**
     * Second pass: after clarification is received, extract from the clarified input.
     */
    public ExtractionResult extractFromClarification(String originalRequest, String clarification, String orgId) {
        if (clarification == null || clarification.isBlank()) {
            return ExtractionResult.unchanged(originalRequest);
        }

        // Try to extract references from the clarification response
        ExtractionResult clarResult = extractAndEnrich(clarification, orgId);

        if (!clarResult.extractedRefs().isEmpty()) {
            // Append context to the original request
            String contextSection = clarResult.enrichedRequest()
                    .substring(clarification.length()); // Get just the context part
            String enriched = originalRequest + contextSection;
            return ExtractionResult.enriched(enriched, clarResult.extractedRefs());
        }

        // If clarification itself didn't yield refs, just append it as additional context
        String enriched = originalRequest + "\n\n## User Clarification\n" + clarification;
        return ExtractionResult.enriched(enriched, List.of());
    }

    // ── Pattern extraction ───────────────────────────────────────────────────

    private Set<String> extractJiraKeys(String text) {
        Set<String> keys = new LinkedHashSet<>();

        // From URLs first (higher priority)
        Matcher urlMatcher = JIRA_URL_PATTERN.matcher(text);
        while (urlMatcher.find()) {
            keys.add(urlMatcher.group(1));
        }

        // From plain text keys
        Matcher keyMatcher = JIRA_KEY_PATTERN.matcher(text);
        while (keyMatcher.find()) {
            keys.add(keyMatcher.group(1));
        }

        return keys;
    }

    private Set<String> extractConfluencePageIds(String text) {
        Set<String> pageIds = new LinkedHashSet<>();

        Matcher matcher = CONFLUENCE_PAGE_URL_PATTERN.matcher(text);
        while (matcher.find()) {
            // Group 1 = /spaces/.../pages/{id}, Group 2 = ?pageId={id}
            String id = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (id != null) {
                pageIds.add(id);
            }
        }

        return pageIds;
    }

    private ExtractionResult checkForVagueMentions(String text) {
        boolean vagueJira = VAGUE_JIRA_MENTION.matcher(text).find();
        boolean vagueConfluence = VAGUE_CONFLUENCE_MENTION.matcher(text).find();

        if (vagueJira && vagueConfluence) {
            return ExtractionResult.needsClarification(text,
                    "Your request mentions JIRA and Confluence resources but doesn't include specific references. "
                    + "Please provide the JIRA issue key (e.g., PROJ-123) and the Confluence page URL.");
        } else if (vagueJira) {
            return ExtractionResult.needsClarification(text,
                    "Your request mentions a JIRA ticket but doesn't include a specific issue key. "
                    + "Please provide the JIRA issue key (e.g., PROJ-123) or a link to the ticket.");
        } else if (vagueConfluence) {
            return ExtractionResult.needsClarification(text,
                    "Your request mentions a Confluence page but doesn't include a link. "
                    + "Please provide the full Confluence page URL.");
        }

        return ExtractionResult.unchanged(text);
    }

    // ── Credential checks ────────────────────────────────────────────────────

    private boolean hasJiraCredentials(Org org) {
        return org.getJiraBaseUrl() != null && !org.getJiraBaseUrl().isBlank()
                && org.getJiraEmail() != null && !org.getJiraEmail().isBlank()
                && org.getJiraApiToken() != null && !org.getJiraApiToken().isBlank();
    }

    private boolean hasConfluenceCredentials(Org org) {
        return org.getConfluenceBaseUrl() != null && !org.getConfluenceBaseUrl().isBlank()
                && org.getConfluenceEmail() != null && !org.getConfluenceEmail().isBlank()
                && org.getConfluenceApiToken() != null && !org.getConfluenceApiToken().isBlank();
    }

    // ── Fetch methods (use org-level credentials) ────────────────────────────

    @SuppressWarnings("unchecked")
    private String fetchJiraIssue(String issueKey, Org org) {
        String url = org.getJiraBaseUrl() + "/rest/api/3/issue/" + issueKey
                + "?fields=summary,description,status,customfield_10034";

        ResponseEntity<Map> resp = integrationRestTemplate.exchange(
                url, HttpMethod.GET, basicAuthEntity(org.getJiraEmail(), org.getJiraApiToken(), null), Map.class);

        Map<String, Object> body = resp.getBody();
        if (body == null) return null;

        Map<String, Object> fields = (Map<String, Object>) body.get("fields");
        if (fields == null) return null;

        String summary = String.valueOf(fields.getOrDefault("summary", ""));
        String description = "";
        Object descObj = fields.get("description");
        if (descObj instanceof String s) {
            description = s;
        } else if (descObj instanceof Map) {
            // ADF JSON — extract text content recursively
            description = extractAdfText(descObj);
        }

        String ac = fields.get("customfield_10034") != null
                ? String.valueOf(fields.get("customfield_10034")) : "";

        Map<String, Object> statusObj = (Map<String, Object>) fields.get("status");
        String status = statusObj != null ? String.valueOf(statusObj.getOrDefault("name", "")) : "";

        StringBuilder sb = new StringBuilder();
        sb.append("### JIRA Issue: ").append(issueKey).append("\n");
        sb.append("**Summary**: ").append(summary).append("\n");
        sb.append("**Status**: ").append(status).append("\n");
        if (!description.isBlank()) {
            sb.append("**Description**:\n").append(description).append("\n");
        }
        if (!ac.isBlank()) {
            sb.append("**Acceptance Criteria**:\n").append(ac).append("\n");
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String fetchConfluencePage(String pageId, Org org) {
        String url = org.getConfluenceBaseUrl() + "/wiki/rest/api/content/" + pageId
                + "?expand=body.storage,version,space";

        ResponseEntity<Map> resp = integrationRestTemplate.exchange(
                url, HttpMethod.GET, basicAuthEntity(org.getConfluenceEmail(), org.getConfluenceApiToken(), null), Map.class);

        Map<String, Object> body = resp.getBody();
        if (body == null) return null;

        String title = String.valueOf(body.getOrDefault("title", "Untitled"));

        Map<String, Object> bodyContent = (Map<String, Object>) body.get("body");
        Map<String, Object> storage = bodyContent != null
                ? (Map<String, Object>) bodyContent.get("storage") : null;
        String html = storage != null ? String.valueOf(storage.get("value")) : "";
        String plainText = stripHtml(html);

        // Truncate to prevent overwhelming the planner (max ~4000 chars)
        if (plainText.length() > 4000) {
            plainText = plainText.substring(0, 4000) + "\n...(truncated)";
        }

        return "### Confluence Page: " + title + "\n" + plainText;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private <T> HttpEntity<T> basicAuthEntity(String email, String apiToken, T body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String encoded = Base64.getEncoder().encodeToString(
                (email + ":" + apiToken).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        headers.set("Authorization", "Basic " + encoded);
        return new HttpEntity<>(body, headers);
    }

    private String stripHtml(String html) {
        if (html == null) return "";
        return html.replaceAll("<[^>]+>", "")
                .replaceAll("&amp;", "&")
                .replaceAll("&lt;", "<")
                .replaceAll("&gt;", ">")
                .replaceAll("&nbsp;", " ")
                .replaceAll("\\s+\n", "\n")
                .trim();
    }

    /**
     * Recursively extracts plain text from Atlassian Document Format (ADF) JSON.
     */
    @SuppressWarnings("unchecked")
    private String extractAdfText(Object node) {
        if (node == null) return "";
        if (node instanceof String s) return s;
        if (!(node instanceof Map)) return "";

        Map<String, Object> map = (Map<String, Object>) node;
        String type = String.valueOf(map.getOrDefault("type", ""));

        // Leaf text node
        if ("text".equals(type) && map.containsKey("text")) {
            return String.valueOf(map.get("text"));
        }

        // Recurse into children
        Object content = map.get("content");
        if (content instanceof List<?> children) {
            StringBuilder sb = new StringBuilder();
            for (Object child : children) {
                String childText = extractAdfText(child);
                if (!childText.isBlank()) {
                    sb.append(childText);
                }
            }
            // Add newline after block-level elements
            if (List.of("paragraph", "heading", "bulletList", "orderedList", "listItem", "blockquote")
                    .contains(type)) {
                sb.append("\n");
            }
            return sb.toString();
        }

        return "";
    }
}
