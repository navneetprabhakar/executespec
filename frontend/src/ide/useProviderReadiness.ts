import {useEffect, useMemo, useState} from 'react';
import * as api from '@/lib/api';
import {readinessByProvider} from '@/ide/providerReadiness';

export function useProviderReadiness() {
  const [rows, setRows] = useState<api.ProviderReadiness[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [supported, setSupported] = useState(true);

  useEffect(() => {
    let active = true;
    setLoading(true);
    let request: Promise<api.ProviderReadinessResponse>;
    try {
      const load = (api as {getProviderReadiness?: typeof api.getProviderReadiness})
        .getProviderReadiness;
      if (typeof load !== 'function') {
        setSupported(false);
        setLoading(false);
        return () => {
          active = false;
        };
      }
      request = load();
    } catch {
      setSupported(false);
      setLoading(false);
      return () => {
        active = false;
      };
    }
    request
      .then((response) => {
        if (!active) return;
        setRows(response.providers ?? []);
        setError(false);
      })
      .catch(() => {
        if (!active) return;
        setRows([]);
        setError(true);
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, []);

  return {
    rows,
    byProvider: useMemo(() => readinessByProvider(rows), [rows]),
    loading,
    error,
    supported,
  };
}
