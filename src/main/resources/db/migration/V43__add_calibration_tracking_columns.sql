-- Add calibration tracking columns to model_task_metric for data-driven complexity tuning.
-- Tracks truncation events, configured vs actual output tokens, and iteration counts.
ALTER TABLE model_task_metric ADD COLUMN IF NOT EXISTS was_truncated BOOLEAN DEFAULT FALSE;
ALTER TABLE model_task_metric ADD COLUMN IF NOT EXISTS max_output_tokens INTEGER DEFAULT 0;
ALTER TABLE model_task_metric ADD COLUMN IF NOT EXISTS actual_output_tokens INTEGER DEFAULT 0;
ALTER TABLE model_task_metric ADD COLUMN IF NOT EXISTS iteration_count INTEGER DEFAULT 0;
