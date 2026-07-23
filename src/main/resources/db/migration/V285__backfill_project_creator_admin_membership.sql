-- Project mutation authorization is membership-based from V285 onward. Every project created by
-- ExecuteSpec already receives a PROJECT_ADMIN row transactionally, but this idempotent backfill
-- repairs imported/legacy rows before the service stops treating same-org membership as write
-- authority.
INSERT INTO project_member (id, project_id, user_id, project_role, assigned_at, is_external)
SELECT
    'creator-admin-' || md5(p.id || ':' || p.created_by),
    p.id,
    p.created_by,
    'PROJECT_ADMIN',
    COALESCE(p.created_at, NOW()),
    FALSE
FROM project p
JOIN app_user u ON u.id = p.created_by
WHERE p.created_by IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM project_member pm
      WHERE pm.project_id = p.id
        AND pm.user_id = p.created_by
  )
ON CONFLICT (project_id, user_id) DO NOTHING;
