import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { useLocation, useNavigationType, useSearchParams } from 'react-router-dom';
import { applicationsApi } from '../api/client';
import { useAuth } from '../auth/AuthProvider';
import { ApplicationPage, ApplicationPageQuery } from '../types';

// Bounded session-memory cache restores a list and scroll on browser Back, scoped by user.
const cache = new Map<string, { data: ApplicationPage; scroll: number }>();
interface PageOptions { fixedGroup?: string; pageParam?: string; scrollTarget?: string; restoreScroll?: boolean; ready?: boolean }
export function useApplicationPage(defaultGroup: string, size: number, groups: string[], sizes: number[], pollMs = 10000, options: PageOptions = {}) {
  const { session } = useAuth();
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const navigation = useNavigationType();
  const query: ApplicationPageQuery = {
    group: options.fixedGroup ?? (groups.includes(params.get('group') ?? '') ? params.get('group')! : defaultGroup),
    page: Math.max(0, Math.min(21474836, Number(params.get(options.pageParam ?? 'page') ?? (params.get('group') === defaultGroup ? params.get('page') : null)) || 0)),
    size: sizes.includes(Number(params.get('size'))) ? Number(params.get('size')) : size,
    q: (params.get('q') ?? '').trim().slice(0, 100),
    sort: ['default', 'oldest', 'newest', 'number_desc', 'number_asc'].includes(params.get('sort') ?? '') ? params.get('sort')! : 'default'
  };
  query.page = Math.floor(query.page);
  const queryKey = JSON.stringify(query);
  const scope = `${session?.user.id}:${session?.user.role}:${session?.user.canSign}:${session?.user.companyId}:`;
  const key = scope + queryKey;
  // Capture before the browser can clamp native Back restoration against a short loading shell.
  const savedPosition = useRef({ entry: location.key, top: cache.get(key)?.scroll ?? 0 });
  if (savedPosition.current.entry !== location.key) savedPosition.current = { entry: location.key, top: cache.get(key)?.scroll ?? 0 };
  const [result, setResult] = useState<{ key: string; data: ApplicationPage; query: ApplicationPageQuery } | null>(null);
  const [incoming, setIncoming] = useState<ApplicationPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [failure, setFailure] = useState<{ key: string; text: string } | null>(null);
  const error = failure?.key === key ? failure.text : null;
  const [search, setSearch] = useState(query.q);
  const sequence = useRef(0);
  const inFlight = useRef(false);
  const controller = useRef<AbortController>();
  const current = useRef(result); current.current = result;
  const pendingScroll = useRef(false);
  const leaving = useRef(false);
  const scrollTarget = options.scrollTarget;
  const restoreScroll = options.restoreScroll !== false;
  const ready = options.ready !== false;
  const update = (patch: Partial<ApplicationPageQuery>, replace = false) => {
    const next = new URLSearchParams(params);
    const merged = { ...query, ...patch };
    for (const [name, value] of Object.entries(merged)) {
      if (name === 'group' && options.fixedGroup) continue;
      next.set(name === 'page' ? options.pageParam ?? 'page' : name, String(value));
    }
    if (options.pageParam) next.delete('page');
    if (patch.q !== undefined || patch.sort !== undefined) {
      next.delete('activePage'); next.delete('donePage'); next.set(options.pageParam ?? 'page', '0');
    }
    // Only a deliberate list navigation scrolls; polls and server page correction do not.
    if (!replace) pendingScroll.current = true;
    setParams(next, { replace });
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
        const actualQuery = { ...JSON.parse(queryKey) as ApplicationPageQuery, page: next.page };
        const actualKey = scope + JSON.stringify(actualQuery);
        setResult({ key: actualKey, data: next, query: actualQuery }); setIncoming(null);
        // Cache under the page the server actually returned BEFORE correcting the URL.
        cache.set(actualKey, { data: next, scroll: cache.get(key)?.scroll ?? 0 });
        while (cache.size > 20) cache.delete(cache.keys().next().value!);
        if (next.page !== (JSON.parse(queryKey) as ApplicationPageQuery).page) updateRef.current({ page: next.page }, true);
      }
      setFailure(null);
    } catch (reason) {
      if (ticket === sequence.current && !abort.signal.aborted) setFailure({ key, text: reason instanceof Error ? reason.message : 'Не удалось загрузить заявки' });
    } finally {
      if (ticket === sequence.current) { inFlight.current = false; setLoading(false); }
    }
  }, [key, queryKey, scope]);
  useEffect(() => {
    sequence.current += 1; inFlight.current = false;
    const saved = cache.get(key);
    setIncoming(null); setFailure(null);
    if (saved) setResult({ key, data: saved.data, query: JSON.parse(queryKey) as ApplicationPageQuery });
    void load(!!saved);
    const timer = window.setInterval(() => { if (!document.hidden) void load(true); }, pollMs);
    return () => {
      sequence.current += 1; controller.current?.abort(); inFlight.current = false; window.clearInterval(timer);
    };
  }, [key, load, pollMs, queryKey]);
  useLayoutEffect(() => {
    leaving.current = false;
    const savePosition = () => {
      if (!leaving.current && current.current?.key === key) cache.set(key, { data: current.current.data, scroll: window.scrollY });
    };
    window.addEventListener('scroll', savePosition, { passive: true });
    // Passive cleanup can run after removing the list, when the browser already clamped scrollY.
    return () => { savePosition(); window.removeEventListener('scroll', savePosition); };
  }, [key]);
  const data = result?.key === key ? result.data : undefined;
  const previousData = result?.key.startsWith(scope) ? result.data : undefined;
  const counts = data?.countsByGroup ?? (result?.key.startsWith(scope) && result.query.q === query.q ? result.data.countsByGroup : undefined);
  const restored = useRef<string | null>(null);
  useEffect(() => {
    if (!data || loading || !ready) return;
    if (pendingScroll.current && navigation !== 'POP' && !location.state?.restoreList) {
      const frame = requestAnimationFrame(() => {
        pendingScroll.current = false;
        const list = scrollTarget ? document.getElementById(scrollTarget) : null;
        const headerBottom = document.querySelector('header')?.getBoundingClientRect().bottom ?? 0;
        if (list) window.scrollTo({ top: Math.max(0, window.scrollY + list.getBoundingClientRect().top - Math.max(0, headerBottom) - 16) });
      });
      return () => cancelAnimationFrame(frame);
    }
    if (restoreScroll && (navigation === 'POP' || location.state?.restoreList) && restored.current !== location.key) {
      pendingScroll.current = false;
      const frame = requestAnimationFrame(() => {
        restored.current = location.key;
        window.scrollTo({ top: savedPosition.current.top });
      });
      return () => cancelAnimationFrame(frame);
    }
  }, [data, key, loading, navigation, location.state, location.key, restoreScroll, scrollTarget, ready]);
  const apply = () => {
    if (!incoming) return;
    const actualQuery = { ...query, page: incoming.page };
    const actualKey = scope + JSON.stringify(actualQuery);
    setResult({ key: actualKey, data: incoming, query: actualQuery }); cache.set(actualKey, { data: incoming, scroll: window.scrollY });
    if (incoming.page !== query.page) update({ page: incoming.page }, true);
    setIncoming(null);
  };
  const rememberPosition = () => {
    if (data) cache.set(key, { data, scroll: window.scrollY });
    leaving.current = true;
  };
  return { data, previousData, counts, loading: loading || (!data && !error), error, reload: () => load(), hasUpdates: !!incoming, apply, query, update, search, setSearch, rememberPosition };
}
