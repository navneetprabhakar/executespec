-- V18: Add admin_email and JIRA/Confluence integration credentials to org table.
-- Integration credentials are managed at the org level by ORG_ADMIN users,
-- replacing the per-user approach and application.yml flags.

ALTER TABLE org ADD COLUMN admin_email VARCHAR(255);
ALTER TABLE org ADD COLUMN jira_base_url TEXT;
ALTER TABLE org ADD COLUMN jira_email TEXT;
ALTER TABLE org ADD COLUMN jira_api_token TEXT;
ALTER TABLE org ADD COLUMN confluence_base_url TEXT;
ALTER TABLE org ADD COLUMN confluence_email TEXT;
ALTER TABLE org ADD COLUMN confluence_api_token TEXT;
