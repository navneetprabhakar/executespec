-- V267: Graduate evidence-gated initial worker routing.
-- InitialRoutingService changes a first attempt only when a candidate has observed quality
-- evidence and the provider is available; otherwise it keeps the caller route unchanged.
UPDATE feature_flag
SET flag_value = 'true',
    description = 'Evidence-gated initial worker provider/model selection; fail-closed without observed quality.'
WHERE flag_key = 'execution.initial-routing.enabled';
