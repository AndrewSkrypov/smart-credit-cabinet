import { Box, Button, LinearProgress, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { ReactNode, useLayoutEffect, useRef } from 'react';
import { useApplicationPage } from '../hooks/useApplicationPage';

type PageState = ReturnType<typeof useApplicationPage>;
const statusSlot = { minHeight: 24, '@media (max-width: 899.95px), (pointer: coarse)': { minHeight: 44 } };
export function ApplicationPageFilters({ state, bank, status = true }: { state: PageState; bank?: boolean; status?: boolean }) {
  return <Stack spacing={0.5}>
    <Stack direction={{ xs: 'column', sm: 'row' }} gap={2}>
      <TextField label={bank ? 'Поиск по номеру, компании или ИНН' : 'Поиск по номеру или продукту'} value={state.search} onChange={(event) => state.setSearch(event.target.value)} inputProps={{ maxLength: 100 }} fullWidth size="small" />
      <TextField select label="Сортировка" size="small" value={state.query.sort} onChange={(event) => state.update({ sort: event.target.value, page: 0 })} sx={{ minWidth: 190 }}>
        {[['default', 'По очереди'], ['oldest', 'Изменены раньше'], ['newest', 'Изменены позже'], ['number_desc', 'Новые номера'], ['number_asc', 'Ранние номера']].map(([value, text]) => <MenuItem key={value} value={value}>{text}</MenuItem>)}
      </TextField>
      {bank && <TextField select label="На странице" size="small" value={state.query.size} onChange={(event) => state.update({ size: Number(event.target.value), page: 0 })} sx={{ minWidth: 110 }}>{[20, 50, 100].map((n) => <MenuItem key={n} value={n}>{n}</MenuItem>)}</TextField>}
    </Stack>
    {status && <ApplicationPageStatus states={[state]} />}
  </Stack>;
}

/** Reserved toolbar line: a poll/update/error never inserts a banner above a reading row. */
export function ApplicationPageStatus({ states }: { states: PageState[] }) {
  const failed = states.find(state => state.error);
  const updated = states.some(state => state.hasUpdates);
  return <Stack direction="row" alignItems="center" gap={1} sx={{ ...statusSlot, minWidth: 0 }}>
    <Typography role="status" variant="caption" color={failed ? 'error' : 'text.secondary'} noWrap sx={{ flex: 1 }} title={failed?.error ?? undefined}>
      {failed ? failed.error : updated ? 'Есть обновления' : states.some(state => state.loading) ? 'Загрузка заявок…' : ''}
    </Typography>
    {updated && <Button size="small" sx={{ ...statusSlot, py: 0 }} onClick={() => states.forEach(state => state.apply())}>Показать обновления</Button>}
    {failed && <Button size="small" sx={{ ...statusSlot, py: 0 }} onClick={() => states.forEach(state => { if (state.error) void state.reload(); })}>Повторить</Button>}
  </Stack>;
}

/** Holds the last rendered geometry while the next query is pending; no stale clickable rows. */
export function ApplicationListRegion({ state, children, id }: { state: PageState; children: ReactNode; id: string }) {
  const root = useRef<HTMLDivElement>(null);
  const height = useRef(200);
  useLayoutEffect(() => {
    const element = root.current;
    if (!element || state.loading || !state.data) return;
    const measure = () => { height.current = element.getBoundingClientRect().height; };
    measure();
    if (typeof ResizeObserver === 'undefined') return;
    const observer = new ResizeObserver(measure); observer.observe(element);
    return () => observer.disconnect();
  }, [state.loading, state.data]);
  const pending = state.loading && !state.data;
  return <Box ref={root} id={id} role="region" aria-label="Список заявок" aria-busy={pending}
    sx={{ position: 'relative', minHeight: !state.data ? Math.max(200, height.current) : undefined, scrollMarginTop: 24 }}>
    {pending ? <LinearProgress aria-label="Загрузка заявок" sx={{ position: 'absolute', top: 0, insetInline: 0 }} /> : children}
  </Box>;
}

export function ApplicationPageNavigation({ state }: { state: PageState }) {
  const data = state.data ?? (state.loading ? state.previousData : undefined);
  if (!data || data.totalPages <= 1) return null;
  return <Stack component="nav" aria-label="Страницы заявок" direction="row" gap={1} alignItems="center" justifyContent="space-between" sx={{ py: 2 }}>
    <Button disabled={state.loading || data.page === 0} onClick={() => state.update({ page: data.page - 1 })}>Назад</Button>
    <Typography variant="body2">{state.loading ? 'Загрузка…' : `${data.page * data.size + 1}–${Math.min((data.page + 1) * data.size, data.totalElements)} из ${data.totalElements}`}</Typography>
    <Button disabled={state.loading || !data.hasNext} onClick={() => state.update({ page: data.page + 1 })}>Далее</Button>
  </Stack>;
}
