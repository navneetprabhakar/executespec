# Audit: `crypto/` — at-rest AES-256-GCM for DB credential columns (standalone package)

Status: **DONE** · Updated 2026-05-29 · 4 files / 394 lines · all read in full

## Files
- `SimpleKeyEncryptionService.java` (204) — AES-256-GCM encrypt/decrypt + key sourcing ✓
- `KeyMigrationRunner.java` (104) — one-time plaintext→encrypted backfill ✓
- `CryptoProperties.java` (45) — `executespec.crypto.*` config ✓
- `EncryptedValueConverter.java` (41) — JPA `AttributeConverter` ✓

## What it does
The at-rest encryption layer for sensitive DB columns. `EncryptedValueConverter` is a JPA `@Convert` applied to credential fields on `AppUser` + `Org` (OpenAI/Anthropic/Gemini/OpenRouter keys, Jira/Confluence tokens, Bitbucket app password). It delegates to `SimpleKeyEncryptionService` (AES-256-GCM, 12-byte random IV, format `Base64(IV‖ct‖tag)`). The 256-bit key is sourced **GCP Secret Manager (prod) → local-key (dev) → passthrough**. `KeyMigrationRunner` backfills any legacy plaintext rows on boot.

> **Distinct from `security/KeyEncryptor`** ([security.md](security.md)): that one also does AES-256-GCM but keys off `SHA-256(app.jwt.secret)` and guards *Redis*-persisted BYOK secrets. This `crypto/` primitive keys off GCP Secret Manager and guards *DB* columns. Two separate encryption systems with two separate key sources — worth consolidating conceptually, but not a defect.

## Findings

### 🟢 Solid AEAD + migration hygiene
- 🟢 Correct AES-256-GCM: random 12-byte IV per encrypt, 128-bit tag, `IV‖ciphertext` Base64. 32-byte key length validated. Key from **GCP Secret Manager** in prod (proper external KMS-ish sourcing, unlike the JWT-secret derivation in `security/KeyEncryptor`). Null-safe converter (null↔null).
- 🟢 **Migration-tolerant decrypt + idempotent backfill.** `decrypt` returns the input as-is when it doesn't look encrypted (`isLikelyEncrypted`: ≥40 chars, Base64-charset, not an `sk-`/`AIza`/`xoxb-` prefix) so legacy plaintext rows read cleanly during the migration window. `KeyMigrationRunner` (on `ApplicationReadyEvent`) re-reads each cred column and writes it back (converter encrypts on write), **skipping already-encrypted rows** via `isLikelyEncrypted` — the H#51 audit fix, because AES-GCM's random IV makes byte-equality always false (naïve re-encrypt-every-boot was the prior bug).

### 🟡 The passthrough foot-gun (the finding that matters)
- 🟡 **Silent plaintext passthrough is the default failure mode.** If `executespec.crypto.enabled=false`, OR no key is configured (no `gcp-secret-name` + no `local-key`), OR the GCP Secret Manager fetch throws — and `require-encryption=false` (**the default**) — then `init()` sets `passthrough=true` and every `encrypt()`/`decrypt()` becomes a **no-op → credentials persist as plaintext** with only a `WARN` log. The H#52 audit added `require-encryption=true` to fail-fast instead, but it's **off by default**, so a production deploy that loses GCP Secret Manager access (IAM drift, secret rotation, network) silently degrades to plaintext credential storage rather than refusing to boot. **Production should pin `executespec.crypto.require-encryption=true`** — verify it's set in the prod profile; if not, that's the single actionable item here.
- 📝 **`decrypt` swallows all failures → returns input as-is.** A genuinely corrupted or tampered ciphertext (not just legacy plaintext) decrypts to "return the raw value" with a debug log, never an error. Migration-friendly but means at-rest tamper/corruption is invisible at read time. Acceptable during the migration window; tighten once backfill is universally complete.
- 📝 **`github_pat` deliberately skips this converter** (plaintext on both `AppUser` + per-run `pipeline_run`) — inconsistent with every other cred, which IS converted. Wiring it through `EncryptedValueConverter` closes the gap ([auth.md](auth.md), [state.md](state.md)).

## Cross-refs
Applied to credential columns on → [auth.md](auth.md) (`AppUser`/`Org` entities). Sibling encryption primitive (Redis, JWT-secret-keyed) → [security.md](security.md) (`KeyEncryptor`). Plaintext outliers it does NOT cover → [auth.md](auth.md) (`github_pat`), [state.md](state.md) (per-run `github_pat`/`bitbucket_app_password`). Audit items H#51 (idempotent backfill) + H#52 (`require-encryption` fail-fast) referenced in-code.
