-- Durable, bounded local-hands call journal. This is recovery evidence, not a durable worker queue:
-- a JVM restart cannot recreate the in-memory CompletableFuture waiting in WorkerAgent.
CREATE TABLE IF NOT EXISTS remote_tool_call (
    call_id VARCHAR(64) PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    task_id VARCHAR(128),
    tool_name VARCHAR(128) NOT NULL,
    args_json TEXT NOT NULL,
    frame_json TEXT NOT NULL,
    state VARCHAR(16) NOT NULL,
    result_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT remote_tool_call_state_ck CHECK (state IN ('PENDING', 'COMPLETED', 'EXPIRED'))
);
CREATE INDEX IF NOT EXISTS idx_remote_tool_call_pending
    ON remote_tool_call (run_id, state, created_at);
