import { useState, useCallback } from 'react';

interface ApiState<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
}

export function useApi<T>(fn: (...args: any[]) => Promise<T>) {
  const [state, setState] = useState<ApiState<T>>({ data: null, loading: false, error: null });

  const call = useCallback(async (...args: any[]) => {
    setState({ data: null, loading: true, error: null });
    try {
      const data = await fn(...args);
      setState({ data, loading: false, error: null });
      return data;
    } catch (e: any) {
      setState({ data: null, loading: false, error: e.message });
      throw e;
    }
  }, [fn]);

  return { ...state, call };
}
