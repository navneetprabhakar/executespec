import {AvailableModelsResponse, req} from './core';

export const getAvailableModels = () => req<AvailableModelsResponse>('/models/available');
