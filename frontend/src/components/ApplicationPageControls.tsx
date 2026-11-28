import { Box, Button, IconButton, InputAdornment, LinearProgress, MenuItem, Stack, TextField, Typography } from '@mui/material';
import CloseRoundedIcon from '@mui/icons-material/CloseRounded';
import SearchRoundedIcon from '@mui/icons-material/SearchRounded';
import { ReactNode, useLayoutEffect, useRef } from 'react';
import { useApplicationPage } from '../hooks/useApplicationPage';
import { tokens } from '../theme/theme';

type PageState = ReturnType<typeof useApplicationPage>;
const narrowOrTouch = '@media (max-width: 899.95px), (pointer: coarse)';
const statusSlot = { minHeight: 36, [narrowOrTouch]: { minHeight: 44 } };
const statusAction = {
  ...statusSlot,
  position: 'relative',
  isolation: 'isolate',
  border: 0,
  bgcolor: 'transparent',
  whiteSpace: 'nowrap',
  // Inset the visible surface while keeping the full touch target.
  '&::before': {
    content: '""',
    position: 'absolute',
    inset: '2px 0',
    zIndex: -1,
    border: '1px solid',
    borderColor: 'divider',
    borderRadius: 'inherit',
    bgcolor: tokens.secondaryFill,
    pointerEvents: 'none',
    [narrowOrTouch]: { insetBlock: '4px' },
  },
  '&:hover': { border: 0, bgcolor: 'transparent' },
  '&:hover::before': { borderColor: 'text.secondary' },
};

// The filters sit on the grey page, not on a card: white wells like the cards around them.
const onPageFields = {
  '& .MuiFilledInput-root': { bgcolor: '#fff', '&:hover': { bgcolor: '#fff' }, '&:hover:not(.Mui-focused):not(.Mui-error)': { boxShadow: `inset 0 0 0 1px ${tokens.line}` }, '&.Mui-focused': { bgcolor: '#fff' } },
};

export function ApplicationPageFilters({ state, bank, status = true }: { state: PageState; bank?: boolean; status?: boolean }) {
  return <Stack>
    <Box sx={{ display: 'grid', gap: { xs: 1, sm: 2 }, ...onPageFields, gridTemplateColumns: {
      xs: bank ? 'minmax(0, 1fr) minmax(0, 110px)' : '1fr',
      sm: bank ? 'minmax(0, 1fr) 190px 110px' : 'minmax(0, 1fr) 190px',
    } }}>
      <TextField hiddenLabel type="search" placeholder={bank ? 'Номер, компания или ИНН' : 'Номер заявки или продукт'} value={state.search}
        onChange={(event) => state.setSearch(event.target.value)} fullWidth size="small"
        inputProps={{ maxLength: 100, 'aria-label': bank ? 'Поиск по номеру, компании или ИНН' : 'Поиск по номеру или продукту' }}
        InputProps={{
          startAdornment: <InputAdornment position="start"><SearchRoundedIcon sx={{ color: 'text.secondary' }} /></InputAdornment>,
          endAdornment: state.search ? (
            <InputAdornment position="end">
              <IconButton size="small" aria-label="Очистить поиск" onClick={() => state.setSearch('')} edge="end"><CloseRoundedIcon fontSize="small" /></IconButton>
            </InputAdornment>
          ) : undefined,
        }}
        sx={{ gridColumn: { xs: '1 / -1', sm: 'auto' }, '& .MuiFilledInput-root': { minHeight: 48 }, '& input::-webkit-search-cancel-button': { display: 'none' } }} />
      <TextField select label="Сортировка" size="small" value={state.query.sort} onChange={(event) => state.update({ sort: event.target.value, page: 0 })}>
        {[['default', 'По очереди'], ['oldest', 'Изменены раньше'], ['newest', 'Изменены позже'], ['number_desc', 'Новые номера'], ['number_asc', 'Ранние номера']].map(([value, text]) => <MenuItem key={value} value={value}>{text}</MenuItem>)}
      </TextField>
      {bank && <TextField select label="На странице" size="small" value={state.query.size} onChange={(event) => state.update({ size: Number(event.target.value), page: 0 })}>{[20, 50, 100].map((n) => <MenuItem key={n} value={n}>{n}</MenuItem>)}</TextField>}
    </Box>
    {status && <ApplicationPageStatus states={[state]} />}
  </Stack>;
}

/** Reserve one status row, with room for actions to wrap on narrow screens. */
export function ApplicationPageStatus({ states }: { states: PageState[] }) {
  const failed = states.find(state => state.error);
  const updated = states.some(state => state.hasUpdates);
  return <Stack direction="row" alignItems="center" gap={1} useFlexGap flexWrap="wrap"
    sx={{ ...statusSlot, boxSizing: 'content-box', py: 1, minWidth: 0 }}>
    <Typography role="status" variant="caption" color={failed ? 'error' : 'text.secondary'} noWrap
      sx={{ minWidth: 0, maxWidth: '100%', fontWeight: 500 }} title={failed?.error ?? undefined}>
      {failed ? failed.error : updated ? 'Есть обновления' : states.some(state => state.loading) ? 'Загрузка заявок…' : ''}
    </Typography>
    {updated && <Button variant="outlined" size="small" sx={statusAction} onClick={() => states.forEach(state => state.apply())}>Показать обновления</Button>}
    {failed && <Button variant="outlined" size="small" sx={statusAction} onClick={() => states.forEach(state => { if (state.error) void state.reload(); })}>Повторить</Button>}
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
