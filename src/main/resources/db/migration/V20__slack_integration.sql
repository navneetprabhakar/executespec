-- V20: Slack integration — org-level OAuth + project-level channel mapping + notification audit.

-- 1. Org-level Slack bot installation (OAuth tokens from Slack V2 flow)
CREATE TABLE org_slack_integration (
    id              BIGSERIAL PRIMARY KEY,
    org_id          VARCHAR(255) NOT NULL REFERENCES org(id) ON DELETE CASCADE,
    team_id         VARCHAR(255) NOT NULL,
    team_name       VARCHAR(255),
    bot_token       TEXT NOT NULL,
    bot_user_id     VARCHAR(255),
    installed_by    VARCHAR(255),
    installed_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_org_slack_team UNIQUE (org_id, team_id)
);

CREATE INDEX idx_org_slack_org ON org_slack_integration(org_id);

-- 2. Project-level channel mapping with verbosity control
CREATE TABLE project_slack_channel (
    id              BIGSERIAL PRIMARY KEY,
    project_id      VARCHAR(255) NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    org_id          VARCHAR(255) NOT NULL REFERENCES org(id) ON DELETE CASCADE,
    channel_id      VARCHAR(255) NOT NULL,
    channel_name    VARCHAR(255),
    verbosity       VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    created_by      VARCHAR(255),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    CONSTRAINT uq_project_slack_channel UNIQUE (project_id, channel_id)
);

CREATE INDEX idx_project_slack_project ON project_slack_channel(project_id);
CREATE INDEX idx_project_slack_org ON project_slack_channel(org_id);

-- 3. Notification audit log
CREATE TABLE slack_notification_log (
    id              BIGSERIAL PRIMARY KEY,
    org_id          VARCHAR(255) NOT NULL,
    run_id          VARCHAR(255),
    channel_id      VARCHAR(255) NOT NULL,
    event_type      VARCHAR(50) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'SENT',
    error_message   TEXT,
    slack_ts        VARCHAR(50),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX idx_slack_notif_log_org ON slack_notification_log(org_id);
CREATE INDEX idx_slack_notif_log_run ON slack_notification_log(run_id);
CREATE INDEX idx_slack_notif_log_created ON slack_notification_log(created_at);
