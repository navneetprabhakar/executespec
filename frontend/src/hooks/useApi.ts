import {useState, useCallback} from 'react';
import {errMsg} from '@/lib/utils';

interface ApiState<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
}

// eslint-disable-next-line @typescript-eslint/no-explicit-any
export function useApi<T>(fn: (...args: any[]) => Promise<T>) {
  const [state, setState] = useState<ApiState<T>>({data: null, loading: false, error: null});

  const call = useCallback(
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    async (...args: any[]) => {
      setState({data: null, loading: true, error: null});
      try {
        const data = await fn(...args);
        setState({data, loading: false, error: null});
        return data;
      } catch (e) {
        setState({data: null, loading: false, error: errMsg(e)});
        throw e;
      }
    },
    [fn],
  );

  return {...state, call};
}
