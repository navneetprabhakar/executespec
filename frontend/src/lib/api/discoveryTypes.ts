// Discovery-library DTOs. Kept separate from the compatibility barrel in core.ts so the
// migration/readymade product surface can evolve without growing the shared API contract file.

export interface MigrationRecipeSummary {
  id: string;
  slug: string;
  name: string;
  tagline: string | null;
  description: string | null;
  sourceStack: string;
  targetStack: string;
  affectedFilesGlob: string;
  category: string;
  tags: string | null;
  tier: 'FREE' | 'PRO' | 'TEAM' | 'ENTERPRISE';
  source: 'FIRST_PARTY' | 'COMMUNITY';
  status: 'LISTED' | 'HIDDEN' | 'ARCHIVED';
  estFilesLow: number | null;
  estFilesHigh: number | null;
  estCreditsLow: string | null;
  estCreditsHigh: string | null;
  iconUrl: string | null;
  thumbnailUrl: string | null;
  samplePrUrl: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface MigrationListItem {
  recipe: MigrationRecipeSummary;
  locked: boolean;
  unlockTier: string | null;
}

export interface MigrationVariant {
  id: string;
  recipeId: string;
  optionsSignature: string;
  options: string;
  specTemplate: string;
  estCreditsLow: string | null;
  estCreditsHigh: string | null;
}

export interface MigrationSlot {
  id: string;
  recipeId: string;
  name: string;
  displayName: string;
  type: 'TEXT' | 'SELECT' | 'SECRET' | 'REPO_PICKER' | 'FILE_PATH' | 'NUMBER' | 'BOOLEAN';
  required: boolean;
  defaultValue: string | null;
  options: string | null;
  description: string | null;
  displayOrder: number;
}

export interface MigrationDependency {
  recipeId: string;
  dependsOnId: string;
  kind: 'PREREQUISITE' | 'RECOMMENDED_AFTER';
}

export interface MigrationDetail {
  recipe: MigrationRecipeSummary;
  variants: MigrationVariant[];
  slots: MigrationSlot[];
  dependencies: MigrationDependency[];
  locked: boolean;
  unlockTier: string | null;
}

export interface MigrationDryRunRepoApplicability {
  repoId: string;
  fullName: string | null;
  status: 'APPLICABLE' | 'INCOMPATIBLE_STACK' | 'UNKNOWN_STACK' | 'NOT_LINKED';
  reason: string | null;
  estFilesLow: number | null;
  estFilesHigh: number | null;
  estCostLow: string | null;
  estCostHigh: string | null;
}

export interface MigrationDryRunResult {
  slug: string;
  name: string;
  estCostLowTotal: string | null;
  estCostHighTotal: string | null;
  estFilesLowTotal: number | null;
  estFilesHighTotal: number | null;
  sampleDiff: string | null;
  perRepo: MigrationDryRunRepoApplicability[];
}

export interface MigrationApplyResponse {
  executionRunId: string;
  status: string;
  message: string;
}

export interface ReadymadeSolutionSummary {
  id: string;
  slug: string;
  name: string;
  tagline: string | null;
  description: string | null;
  category: string;
  macroGroup: string;
  tags: string | null;
  kind: 'PRIMITIVE' | 'STACK' | 'INTEGRATION';
  tier: 'FREE' | 'PRO' | 'TEAM' | 'ENTERPRISE';
  source: 'FIRST_PARTY' | 'COMMUNITY';
  status: 'LISTED' | 'HIDDEN' | 'ARCHIVED';
  iconUrl: string | null;
  thumbnailUrl: string | null;
  samplePrUrl: string | null;
  estFilesLow: number | null;
  estFilesHigh: number | null;
  estCreditsLow: string | null;
  estCreditsHigh: string | null;
  authorUserId: string | null;
  authorOrgId: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ReadymadeListItem {
  solution: ReadymadeSolutionSummary;
  locked: boolean;
  unlockTier: string | null;
}

export interface ReadymadeVariant {
  id: string;
  solutionId: string;
  language: string;
  framework: string;
  options: string | null;
  specTemplate: string;
  scaffoldKey: string | null;
  estCreditsLow: string | null;
  estCreditsHigh: string | null;
  createdAt: string;
}

export interface ReadymadeSlot {
  id: string;
  solutionId: string;
  name: string;
  displayName: string;
  type: 'TEXT' | 'SELECT' | 'SECRET' | 'REPO_PICKER' | 'FILE_PATH' | 'NUMBER' | 'BOOLEAN';
  required: boolean;
  defaultValue: string | null;
  options: string | null;
  description: string | null;
  displayOrder: number;
}

export interface ReadymadeDependency {
  solutionId: string;
  dependsOnId: string;
  required: boolean;
}

export interface ReadymadeDetail {
  solution: ReadymadeSolutionSummary;
  variants: ReadymadeVariant[];
  slots: ReadymadeSlot[];
  dependencies: ReadymadeDependency[];
  locked: boolean;
  unlockTier: string | null;
}

export interface VariantResolutionResult {
  bestMatch: ReadymadeVariant | null;
  availableVariants: ReadymadeVariant[];
  detectedLanguage: string | null;
  detectedFramework: string | null;
  reason: 'OK' | 'NO_VARIANT_FOR_STACK' | 'STACK_NOT_DETECTED' | 'REPO_NOT_LINKED';
}

export interface LibraryFacets {
  categories: Record<string, number>;
  macroGroups: Record<string, number>;
}
