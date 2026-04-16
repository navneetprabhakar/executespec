-- V104: Add project-level GitHub owner default.
-- Enterprise projects can target different GitHub orgs; this field pre-fills
-- the githubOwner on new-repo specs during a Launch so users don't have to
-- type it every time.
ALTER TABLE project ADD COLUMN github_owner VARCHAR(255);
