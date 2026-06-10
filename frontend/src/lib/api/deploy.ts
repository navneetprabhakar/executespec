import {
  CredentialSummary,
  DeployAnalysisResult,
  DeploymentSummary,
  ValidationResult,
  req,
} from './core';

// v2.5.13 — feasibility() client retired; POST /api/feasibility is permanently
// 410 Gone on V2. Use the plan-approval cost estimate
// (RunDetail.costEstimateLow/High) instead.

export const getDeployments = () => req<DeploymentSummary[]>('/deployments');

export const createDeployment = (body: {runId: string; strategy?: string}) =>
  req<DeploymentSummary>('/deployments', {method: 'POST', body: JSON.stringify(body)});

export const deleteDeployment = (id: string) => req<void>(`/deployments/${id}`, {method: 'DELETE'});

export const rollbackDeployment = (id: string) =>
  req<DeploymentSummary>(`/deployments/${id}/rollback`, {method: 'POST'});

export const analyzeForDeploy = (runId: string) =>
  req<DeployAnalysisResult>(`/deployments/analyze?runId=${encodeURIComponent(runId)}`, {
    method: 'POST',
  });

export const confirmDeploy = (body: {
  runId: string;
  deploymentPath: string;
  region?: string;
  envVars?: Record<string, string>;
  manifestJson?: string;
  healthCheckPath?: string;
  appPort?: number;
}) => req<DeploymentSummary>('/deployments/confirm', {method: 'POST', body: JSON.stringify(body)});

export const getDeploymentDetail = (id: string) => req<DeploymentSummary>(`/deployments/${id}`);

export const storeGcpCredential = (json: string, projectId?: string) => {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
  return req<CredentialSummary>(`/credentials/gcp${qs}`, {
    method: 'POST',
    body: json,
  });
};

export const getGcpCredential = (projectId?: string) => {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
  return req<CredentialSummary | null>(`/credentials/gcp${qs}`);
};

export const listGcpCredentials = () => req<CredentialSummary[]>('/credentials/gcp/all');

export const validateGcpCredential = (projectId?: string) => {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
  return req<ValidationResult>(`/credentials/gcp/validate${qs}`);
};

export const deleteGcpCredential = (projectId?: string) => {
  const qs = projectId ? `?projectId=${encodeURIComponent(projectId)}` : '';
  return req<{message: string}>(`/credentials/gcp${qs}`, {method: 'DELETE'});
};
