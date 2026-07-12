-- ExecuteSpec 2.0: fail closed on malformed executable model overrides that still mention the
-- retired Sarvam route. V256 cleans valid JSON objects, but intentionally retained malformed text
-- so one bad row could not block a deploy. That leaves an operator-editable configuration value
-- containing a removed provider. Configuration is executable state, so clear only those malformed
-- rows; immutable usage, benchmark, and event history remain untouched.
DO $$
DECLARE
  item RECORD;
  cleaned JSONB;
BEGIN
  FOR item IN
    SELECT id, model_overrides_json
    FROM org
    WHERE model_overrides_json IS NOT NULL
      AND LOWER(model_overrides_json) LIKE '%sarvam%'
  LOOP
    BEGIN
      IF jsonb_typeof(item.model_overrides_json::jsonb) <> 'object' THEN
        UPDATE org SET model_overrides_json = NULL WHERE id = item.id;
      ELSE
        SELECT NULLIF(jsonb_object_agg(entry.key, entry.value), '{}'::jsonb)
          INTO cleaned
        FROM jsonb_each(item.model_overrides_json::jsonb) AS entry(key, value)
        WHERE LOWER(entry.key) NOT LIKE 'sarvam:%'
          AND LOWER(COALESCE(entry.value #>> '{}', '')) NOT LIKE 'sarvam-%';
        UPDATE org SET model_overrides_json = cleaned WHERE id = item.id;
      END IF;
    EXCEPTION WHEN others THEN
      -- Invalid JSON cannot be safely filtered. Remove the executable override rather than
      -- leaving a retired provider token discoverable to a lenient reader.
      UPDATE org SET model_overrides_json = NULL WHERE id = item.id;
    END;
  END LOOP;
END $$;
