import { DependencyList, useCallback, useEffect, useRef, useState } from 'react';

/**
 * Loads data when the component mounts and whenever `deps` change. Only the answer to the
 * latest request is kept: a slower, older response never overwrites a newer one.
 */
export function useApi<T>(loader: () => Promise<T>, deps: DependencyList = []) {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  // Always the loader of the latest render; `deps` decide when it runs.
  const loaderRef = useRef(loader);
  loaderRef.current = loader;
  const latestRequest = useRef(0);

  const reload = useCallback(async () => {
    const request = ++latestRequest.current;
    setLoading(true);
    setError(null);
    try {
      const next = await loaderRef.current();
      if (request === latestRequest.current) setData(next);
    } catch (nextError) {
      if (request === latestRequest.current) setError(nextError instanceof Error ? nextError.message : 'Ошибка загрузки');
    } finally {
      if (request === latestRequest.current) setLoading(false);
    }
  }, []);

  useEffect(() => {
    void reload();
    // The caller lists what its loader depends on, as with useEffect.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);

  return { data, loading, error, reload };
}
