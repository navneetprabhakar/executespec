import { req } from './api';
import type {
  ExecutionRunDto,
  CreateExecutionRequest,
  ArchApprovalRequest,
  DecisionRequest,
} from '../types/execution';

export const executionApi = {
  create: (projectId: string, body: CreateExecutionRequest) =>
    req<ExecutionRunDto>(`/projects/${projectId}/executions`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  get: (id: string) => req<ExecutionRunDto>(`/executions/${id}`),

  list: (projectId: string, page = 0, size = 20) =>
    req<{ content: ExecutionRunDto[]; totalElements: number }>(
      `/projects/${projectId}/executions?page=${page}&size=${size}`,
    ),

  triggerAnalysis: (id: string) =>
    req<void>(`/executions/${id}/analyse`, { method: 'POST' }),

  approveArch: (id: string, body: ArchApprovalRequest) =>
    req<void>(`/executions/${id}/arch-review`, {
      method: 'PUT',
      body: JSON.stringify(body),
    }),

  generatePlan: (id: string) =>
    req<void>(`/executions/${id}/plan`, { method: 'POST' }),

  approvePlan: (id: string) =>
    req<void>(`/executions/${id}/plan/approve`, { method: 'PUT' }),

  decide: (id: string, solutionId: string, body: DecisionRequest) =>
    req<void>(`/executions/${id}/solutions/${solutionId}/decide`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),

  signalManualReady: (id: string, solutionId: string) =>
    req<void>(`/executions/${id}/solutions/${solutionId}/manual-ready`, {
      method: 'POST',
    }),

  abort: (id: string) =>
    req<void>(`/executions/${id}/abort`, { method: 'POST' }),

  getReport: (id: string) =>
    req<{ markdown: string; status: string; solutionCount: number }>(
      `/executions/${id}/report`,
    ),

  getSseToken: (id: string) =>
    req<{ token: string }>(`/executions/${id}/sse-token`),
};
