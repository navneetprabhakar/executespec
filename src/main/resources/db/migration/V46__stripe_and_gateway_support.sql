-- V46: Add Stripe support columns and gateway tracking across billing tables

-- Org: Stripe customer ID and preferred payment gateway
ALTER TABLE org ADD COLUMN IF NOT EXISTS stripe_customer_id VARCHAR(255);
ALTER TABLE org ADD COLUMN IF NOT EXISTS payment_gateway VARCHAR(20) DEFAULT 'razorpay';

-- AppUser: Stripe customer ID and preferred payment gateway
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS stripe_customer_id VARCHAR(255);
ALTER TABLE app_user ADD COLUMN IF NOT EXISTS payment_gateway VARCHAR(20) DEFAULT 'razorpay';

-- BillingSubscription: gateway tracking and billing entity reference
ALTER TABLE billing_subscription ADD COLUMN IF NOT EXISTS gateway VARCHAR(20) DEFAULT 'razorpay';
ALTER TABLE billing_subscription ADD COLUMN IF NOT EXISTS billing_entity_type VARCHAR(10);
ALTER TABLE billing_subscription ADD COLUMN IF NOT EXISTS billing_entity_id VARCHAR(255);

-- Invoice: gateway tracking and Stripe invoice references
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS gateway VARCHAR(20) DEFAULT 'razorpay';
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS gateway_invoice_id VARCHAR(255);
ALTER TABLE invoice ADD COLUMN IF NOT EXISTS gateway_pdf_url TEXT;
