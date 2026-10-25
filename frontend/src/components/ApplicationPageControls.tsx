import { Alert, Button, MenuItem, Stack, TextField, Typography } from '@mui/material';
import { useApplicationPage } from '../hooks/useApplicationPage';

type PageState = ReturnType<typeof useApplicationPage>;
export function ApplicationPageFilters({ state, bank }: { state: PageState; bank?: boolean }) {
  return <Stack spacing={2} sx={{ mb: 2 }}>
    <Stack direction={{ xs: 'column', sm: 'row' }} gap={2}>
      <TextField label={bank ? 'Поиск по номеру, компании или ИНН' : 'Поиск по номеру или продукту'} value={state.search} onChange={(event) => state.setSearch(event.target.value)} inputProps={{ maxLength: 100 }} fullWidth size="small" />
      <TextField select label="Сортировка" size="small" value={state.query.sort} onChange={(event) => state.update({ sort: event.target.value, page: 0 })} sx={{ minWidth: 190 }}>
        {[['default', 'По очереди'], ['oldest', 'Изменены раньше'], ['newest', 'Изменены позже'], ['number_desc', 'Новые номера'], ['number_asc', 'Ранние номера']].map(([value, text]) => <MenuItem key={value} value={value}>{text}</MenuItem>)}
      </TextField>
      {bank && <TextField select label="На странице" size="small" value={state.query.size} onChange={(event) => state.update({ size: Number(event.target.value), page: 0 })} sx={{ minWidth: 110 }}>{[20, 50, 100].map((n) => <MenuItem key={n} value={n}>{n}</MenuItem>)}</TextField>}
    </Stack>
    {state.hasUpdates && <Alert severity="info" action={<Button onClick={state.apply}>Показать обновления</Button>}>Есть обновления. Видимый список сохранён.</Alert>}
    {state.error && state.data && <Alert severity="warning" action={<Button onClick={() => void state.reload()}>Повторить</Button>}>{state.error}. Показаны последние полученные сведения.</Alert>}
  </Stack>;
}

export function ApplicationPageNavigation({ state }: { state: PageState }) {
  const data = state.data;
  if (!data || !data.totalElements) return null;
  return <Stack component="nav" aria-label="Страницы заявок" direction="row" gap={1} alignItems="center" justifyContent="space-between" sx={{ py: 2 }}>
    <Button disabled={state.loading || data.page === 0} onClick={() => state.update({ page: data.page - 1 })}>Назад</Button>
    <Typography variant="body2">{data.page * data.size + 1}–{Math.min((data.page + 1) * data.size, data.totalElements)} из {data.totalElements}</Typography>
    <Button disabled={state.loading || !data.hasNext} onClick={() => state.update({ page: data.page + 1 })}>Далее</Button>
  </Stack>;
}
