-- GCS workspace archiving: track when a run's workspace has been archived to Cloud Storage.
-- gcs_workspace_key: GCS object path (e.g. workspaces/{runId}.zip); null = files are local
-- gcs_archived_at:   timestamp of when archival completed

ALTER TABLE pipeline_run
    ADD COLUMN gcs_workspace_key TEXT        DEFAULT NULL,
    ADD COLUMN gcs_archived_at   TIMESTAMPTZ DEFAULT NULL;
