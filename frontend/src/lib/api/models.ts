import {AvailableModelsResponse, req} from './core';

export type ProviderReadinessState =
  | 'AVAILABLE_MANAGED'
  | 'AVAILABLE_USER_KEY'
  | 'AVAILABLE_ORG_KEY'
  | 'POLICY_BLOCKED'
  | 'CIRCUIT_OPEN'
  | 'UNAVAILABLE';

export interface ProviderReadiness {
  provider: string;
  state: ProviderReadinessState;
  dispatchable: boolean;
  keySourceMode: string;
  reason: string;
}

export interface ProviderReadinessResponse {
  schemaVersion: number;
  generatedAt: string;
  providers: ProviderReadiness[];
}

export interface UserModel {
  id: string;
  provider: string;
  label: string;
  minTier: string;
  available: boolean;
}

export const getAvailableModels = () => req<AvailableModelsResponse>('/models/available');
export const getMyModels = () => req<UserModel[]>('/models/me');
export const getProviderReadiness = () => req<ProviderReadinessResponse>('/v3/provider-readiness');
