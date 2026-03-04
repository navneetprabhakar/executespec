-- V9: Post-run conversation history and iteration linkage
--
-- chat_history_json   — JSON array of {role, content} message objects
-- chat_message_count  — total messages exchanged (user + assistant combined)
-- source_run_id       — when this run was created as an iteration of a previous run,
--                       points to the run whose workspace was copied in

ALTER TABLE pipeline_run
    ADD COLUMN chat_history_json  TEXT          DEFAULT NULL,
    ADD COLUMN chat_message_count INTEGER       DEFAULT 0,
    ADD COLUMN source_run_id      VARCHAR(36)   DEFAULT NULL;
