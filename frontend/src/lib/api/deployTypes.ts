/** Deployment and GCP credential DTOs shared by the deployment API surface. */

export interface DeploymentSummary {
  id: string;
  runId: string;
  versionTag: string;
  status: string;
  strategy: string;
  serviceUrl: string;
  revisionName: string;
  createdAt: string;
  ttlExpiresAt: string;
}

export interface RequiredPermission {
  role: string;
  label: string;
  reason: string;
  required: boolean;
}

export interface PermissionCheck {
  role: string;
  label: string;
  required: boolean;
  granted: boolean;
  detail: string;
}

export interface CredentialSummary {
  id: string;
  gcpProjectId: string;
  serviceAccountEmail: string;
  projectId: string | null;
  createdAt: string;
  validatedAt: string | null;
}

export interface GcpValidationResult {
  valid: boolean;
  issues: string[];
  gcpProjectId: string | null;
  permissionChecks: PermissionCheck[];
}

export interface DeployAnalysisResult {
  manifest: {
    projectType: string;
    language: string;
    framework: string;
    buildTool: string;
    detectedPort: number;
    healthCheckPath: string;
    databases: string[];
    caches: string[];
    queues: string[];
    storageServices: string[];
    envVars: string[];
    isStateless: boolean;
    hasHealthCheck: boolean;
    hasDockerfile: boolean;
    dockerfilePath: string | null;
  };
  decision: {
    recommendedPath: string;
    availablePaths: Array<{
      path: string;
      label: string;
      description: string;
      available: boolean;
      reason: string;
    }>;
    reasoning: string;
  };
  hasGcpCredentials: boolean;
}
