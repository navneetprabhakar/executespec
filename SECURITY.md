# Security

Report vulnerabilities privately to admin@executespec.ai, or through GitHub private vulnerability
reporting when enabled. Include affected revisions, a minimal reproduction, and the impact. Do not
send working credentials or customer data, and do not open a public issue containing a secret.
There is no guaranteed response-time SLA for this experimental project.

Generated code and repository-supplied commands are executable untrusted input. A development
configuration is not an appropriate production isolation boundary. Review sandbox, provider-spend,
tenant, authentication, and publication policies before operating the service for other users.

## Credentials and history

Use environment variables or a secret manager. Never commit local configuration, tokens, auth
responses, service-account keys, or Terraform state/plan files. Security test fixtures must be
explicitly synthetic and scanner exceptions must be scoped to exact fixtures.

If a credential reaches Git, revoke or rotate it. Removing the latest copy or rewriting history
cannot invalidate a credential or erase existing clones, cached views, or downloaded artifacts.

Before public release, scan both the release tree and every branch/tag that will remain public.
Internal captures, old branches, pull-request content, and workflow artifacts need separate review.
The public-release repository guard checks tracked source hygiene; it does not replace secret
scanning or establish deployment security.
