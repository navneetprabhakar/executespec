-- Subtask decomposition: multi-file tasks split into per-file subtasks
ALTER TABLE agent_task ADD COLUMN parent_task_id VARCHAR(255);
ALTER TABLE agent_task ADD COLUMN subtask_index  INTEGER;
ALTER TABLE agent_task ADD COLUMN subtask_total  INTEGER;

CREATE INDEX idx_agent_task_parent ON agent_task(parent_task_id);
