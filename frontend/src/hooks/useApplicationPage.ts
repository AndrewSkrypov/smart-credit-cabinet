import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigationType, useSearchParams } from 'react-router-dom';
import { applicationsApi } from '../api/client';
import { useAuth } from '../auth/AuthProvider';
import { ApplicationPage, ApplicationPageQuery } from '../types';

// Bounded session-memory cache restores a list and scroll on browser Back, scoped by user.
const cache = new Map<string, { data: ApplicationPage; scroll: number }>();
export function useApplicationPage(defaultGroup: string, size: number, groups: string[], sizes: number[], pollMs = 10000) {
  const { session } = useAuth();
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const navigation = useNavigationType();
  const query: ApplicationPageQuery = {
    group: groups.includes(params.get('group') ?? '') ? params.get('group')! : defaultGroup,
    page: Math.max(0, Math.min(21474836, Number(params.get('page')) || 0)),
    size: sizes.includes(Number(params.get('size'))) ? Number(params.get('size')) : size,
    q: (params.get('q') ?? '').trim().slice(0, 100),
    sort: ['default', 'oldest', 'newest', 'number_desc', 'number_asc'].includes(params.get('sort') ?? '') ? params.get('sort')! : 'default'
  };
  query.page = Math.floor(query.page);
  const queryKey = JSON.stringify(query);
  const key = `${session?.user.id}:${session?.user.role}:${session?.user.canSign}:${session?.user.companyId}:${queryKey}`;
  const [result, setResult] = useState<{ key: string; data: ApplicationPage } | null>(null);
  const [incoming, setIncoming] = useState<ApplicationPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<{ key: string; text: string } | null>(null);
  const error = failure?.key === key ? failure.text : null;
  const [search, setSearch] = useState(query.q);
  const sequence = useRef(0);
  const inFlight = useRef(false);
  const controller = useRef<AbortController>();
  const current = useRef(result); current.current = result;
  const update = (patch: Partial<ApplicationPageQuery>, replace = false) => {
    setParams(Object.fromEntries(Object.entries({ ...query, ...patch }).map(([k, v]) => [k, String(v)])), { replace });
  };
  const updateRef = useRef(update); updateRef.current = update;
  useEffect(() => { setSearch(query.q); }, [query.q]);
  useEffect(() => {
    if (search.trim() === query.q) return;
    const timer = window.setTimeout(() => updateRef.current({ q: search.trim(), page: 0 }), 300);
    return () => window.clearTimeout(timer);
  }, [search, query.q]);

  const load = useCallback(async (background = false) => {
    if (inFlight.current) return;
    inFlight.current = true;
    const ticket = ++sequence.current;
    const abort = new AbortController(); controller.current = abort;
    if (!background) setLoading(true);
    try {
      const next = await applicationsApi.page(JSON.parse(queryKey) as ApplicationPageQuery, abort.signal);
      if (ticket !== sequence.current) return;
      if (background && current.current?.key === key) {
        setIncoming(JSON.stringify(current.current.data) !== JSON.stringify(next) ? next : null);
      } else {
        setResult({ key, data: next }); setIncoming(null);
        cache.set(key, { data: next, scroll: cache.get(key)?.scroll ?? 0 });
        while (cache.size > 20) cache.delete(cache.keys().next().value!);
        if (next.page !== (JSON.parse(queryKey) as ApplicationPageQuery).page) updateRef.current({ page: next.page }, true);
      }
      setFailure(null);
    } catch (reason) {
      if (ticket === sequence.current && !abort.signal.aborted) setFailure({ key, text: reason instanceof Error ? reason.message : 'Не удалось загрузить заявки' });
    } finally {
      if (ticket === sequence.current) { inFlight.current = false; setLoading(false); }
    }
  }, [key, queryKey]);
  useEffect(() => {
    sequence.current += 1; inFlight.current = false;
    const saved = cache.get(key);
    setIncoming(null); setFailure(null);
    if (saved) setResult({ key, data: saved.data });
    void load(!!saved);
    const timer = window.setInterval(() => { if (!document.hidden) void load(true); }, pollMs);
    return () => {
      if (current.current?.key === key) cache.set(key, { data: current.current.data, scroll: window.scrollY });
      sequence.current += 1; controller.current?.abort(); inFlight.current = false; window.clearInterval(timer);
    };
  }, [key, load, pollMs]);
  const data = result?.key === key ? result.data : undefined;
  const restored = useRef<string | null>(null);
  useEffect(() => {
    if ((navigation === 'POP' || location.state?.restoreList) && data && restored.current !== key) {
      restored.current = key;
      const frame = requestAnimationFrame(() => window.scrollTo({ top: cache.get(key)?.scroll ?? 0 }));
      return () => cancelAnimationFrame(frame);
    }
  }, [data, key, navigation, location.state]);
  const apply = () => {
    if (!incoming) return;
    setResult({ key, data: incoming }); cache.set(key, { data: incoming, scroll: window.scrollY });
    if (incoming.page !== query.page) update({ page: incoming.page }, true);
    setIncoming(null);
  };
  return { data, loading: loading || (!data && !error), error, reload: () => load(), hasUpdates: !!incoming, apply, query, update, search, setSearch };
}
