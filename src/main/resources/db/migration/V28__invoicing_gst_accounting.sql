-- ============================================================
-- V28: Invoicing, GST Compliance & Accounting
-- Adds billing profiles (GST/address), invoices with tax breakdown,
-- accounting ledger (double-entry), and seller config.
-- ============================================================

-- ── Billing Profile (GST/address per user or org) ──────────────────────────

CREATE TABLE billing_profile (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             VARCHAR(255) REFERENCES app_user(id),
    org_id              VARCHAR(255) REFERENCES org(id),

    -- Company / individual info
    legal_name          VARCHAR(255) NOT NULL,
    billing_email       VARCHAR(255),
    phone               VARCHAR(20),

    -- GST details
    gstin               VARCHAR(15),
    gst_registered      BOOLEAN NOT NULL DEFAULT FALSE,
    pan                 VARCHAR(10),

    -- Address
    address_line1       VARCHAR(255),
    address_line2       VARCHAR(255),
    city                VARCHAR(100),
    state_code          VARCHAR(2),
    state_name          VARCHAR(100),
    postal_code         VARCHAR(10),
    country             VARCHAR(2) NOT NULL DEFAULT 'IN',

    -- Currency preference
    preferred_currency  VARCHAR(3) NOT NULL DEFAULT 'INR',

    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_billing_profile_owner CHECK (user_id IS NOT NULL OR org_id IS NOT NULL),
    CONSTRAINT chk_gstin_format CHECK (
        gstin IS NULL OR gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z]{1}[1-9A-Z]{1}Z[0-9A-Z]{1}$'
    )
);

CREATE UNIQUE INDEX idx_billing_profile_user ON billing_profile(user_id) WHERE user_id IS NOT NULL;
CREATE UNIQUE INDEX idx_billing_profile_org  ON billing_profile(org_id)  WHERE org_id IS NOT NULL;

-- ── Invoice ────────────────────────────────────────────────────────────────

CREATE TABLE invoice (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Sequential number: ES-2526-0001
    invoice_number      VARCHAR(20) NOT NULL UNIQUE,
    financial_year      VARCHAR(5) NOT NULL,
    sequence_in_year    INT NOT NULL,

    -- Relations
    user_id             VARCHAR(255) NOT NULL REFERENCES app_user(id),
    org_id              VARCHAR(255) REFERENCES org(id),
    billing_profile_id  UUID REFERENCES billing_profile(id),
    subscription_id     UUID REFERENCES billing_subscription(id),
    topup_id            UUID REFERENCES run_topup(id),

    -- Type: SUBSCRIPTION, TOPUP, CREDIT_NOTE
    invoice_type        VARCHAR(20) NOT NULL,
    -- Status: DRAFT, ISSUED, PAID, CANCELLED, VOID
    status              VARCHAR(20) NOT NULL DEFAULT 'DRAFT',

    -- Amounts in paise (smallest currency unit)
    currency            VARCHAR(3) NOT NULL DEFAULT 'INR',
    subtotal            BIGINT NOT NULL,
    cgst_amount         BIGINT NOT NULL DEFAULT 0,
    sgst_amount         BIGINT NOT NULL DEFAULT 0,
    igst_amount         BIGINT NOT NULL DEFAULT 0,
    total_tax           BIGINT NOT NULL DEFAULT 0,
    total_amount        BIGINT NOT NULL,

    -- GST rates
    cgst_rate           NUMERIC(5,2) DEFAULT 0,
    sgst_rate           NUMERIC(5,2) DEFAULT 0,
    igst_rate           NUMERIC(5,2) DEFAULT 0,
    place_of_supply     VARCHAR(2),
    is_reverse_charge   BOOLEAN NOT NULL DEFAULT FALSE,
    is_export           BOOLEAN NOT NULL DEFAULT FALSE,
    sac_code            VARCHAR(10) DEFAULT '998314',

    -- Seller snapshot
    seller_gstin        VARCHAR(15),
    seller_name         VARCHAR(255),
    seller_address      TEXT,
    seller_state_code   VARCHAR(2),

    -- Buyer snapshot
    buyer_gstin         VARCHAR(15),
    buyer_name          VARCHAR(255),
    buyer_address       TEXT,
    buyer_state_code    VARCHAR(2),

    -- Payment reference
    gateway_payment_id  VARCHAR(100),
    gateway_order_id    VARCHAR(100),
    payment_method      VARCHAR(50),
    paid_at             TIMESTAMPTZ,

    -- PDF
    pdf_storage_path    VARCHAR(500),
    pdf_generated_at    TIMESTAMPTZ,

    -- Credit note reference
    original_invoice_id UUID REFERENCES invoice(id),
    credit_note_reason  TEXT,

    -- Dates
    invoice_date        DATE NOT NULL,
    due_date            DATE,

    description         TEXT,
    notes               TEXT,
    metadata            JSONB,

    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_invoice_user     ON invoice(user_id);
CREATE INDEX idx_invoice_org      ON invoice(org_id);
CREATE INDEX idx_invoice_type     ON invoice(invoice_type);
CREATE INDEX idx_invoice_status   ON invoice(status);
CREATE INDEX idx_invoice_date     ON invoice(invoice_date);
CREATE INDEX idx_invoice_fy       ON invoice(financial_year);
CREATE INDEX idx_invoice_payment  ON invoice(gateway_payment_id) WHERE gateway_payment_id IS NOT NULL;

-- ── Invoice Line Items ─────────────────────────────────────────────────────

CREATE TABLE invoice_line_item (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id      UUID NOT NULL REFERENCES invoice(id) ON DELETE CASCADE,

    description     VARCHAR(500) NOT NULL,
    sac_code        VARCHAR(10) DEFAULT '998314',
    quantity        INT NOT NULL DEFAULT 1,
    unit_price      BIGINT NOT NULL,
    amount          BIGINT NOT NULL,

    sort_order      INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_invoice_line_item_invoice ON invoice_line_item(invoice_id);

-- ── Invoice Sequence (thread-safe FY-based numbering) ──────────────────────

CREATE TABLE invoice_sequence (
    financial_year  VARCHAR(5) PRIMARY KEY,
    last_number     INT NOT NULL DEFAULT 0
);

-- Seed current FY (Apr 2025 - Mar 2026)
INSERT INTO invoice_sequence (financial_year, last_number) VALUES ('2526', 0);

-- ── Accounting Ledger (double-entry) ───────────────────────────────────────

CREATE TABLE accounting_entry (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    journal_ref     VARCHAR(50) NOT NULL,

    -- REVENUE, GST_OUTPUT, ACCOUNTS_RECEIVABLE, BANK, REFUND
    account         VARCHAR(50) NOT NULL,

    -- DEBIT or CREDIT
    entry_type      VARCHAR(6) NOT NULL,

    amount          BIGINT NOT NULL,
    currency        VARCHAR(3) NOT NULL DEFAULT 'INR',

    invoice_id      UUID REFERENCES invoice(id),
    user_id         VARCHAR(255) REFERENCES app_user(id),
    org_id          VARCHAR(255) REFERENCES org(id),

    description     TEXT,
    entry_date      DATE NOT NULL,

    period_start    DATE,
    period_end      DATE,

    metadata        JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_accounting_entry_account  ON accounting_entry(account);
CREATE INDEX idx_accounting_entry_date     ON accounting_entry(entry_date);
CREATE INDEX idx_accounting_entry_journal  ON accounting_entry(journal_ref);
CREATE INDEX idx_accounting_entry_invoice  ON accounting_entry(invoice_id);

-- ── Seller Config (ExecuteSpec's own GST/business details) ─────────────────

CREATE TABLE seller_config (
    id              VARCHAR(20) PRIMARY KEY DEFAULT 'DEFAULT',
    legal_name      VARCHAR(255) NOT NULL,
    gstin           VARCHAR(15) NOT NULL,
    pan             VARCHAR(10),
    address_line1   VARCHAR(255),
    address_line2   VARCHAR(255),
    city            VARCHAR(100),
    state_code      VARCHAR(2) NOT NULL,
    state_name      VARCHAR(100),
    postal_code     VARCHAR(10),
    country         VARCHAR(2) NOT NULL DEFAULT 'IN',

    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Seed with placeholder — admin must update with real GSTIN
INSERT INTO seller_config (id, legal_name, gstin, state_code, state_name, city, country)
VALUES ('DEFAULT', 'ExecuteSpec Technologies', '10XXXXX0000X1ZX', '10', 'Bihar', 'Patna', 'IN');

-- ── Document FINANCE as valid global_role ──────────────────────────────────

COMMENT ON COLUMN app_user.global_role IS 'Valid values: SUPER_ADMIN | USER | FINANCE';
