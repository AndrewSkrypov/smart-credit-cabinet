import { Box, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { applicationsApi } from '../../../api/client';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingBlock } from '../../../components/LoadingBlock';
import { TableScrollRegion } from '../../../components/TableScrollRegion';
import { useApi } from '../../../hooks/useApi';
import { rubExact } from '../../../shared/format';

/** One server calculation for the offer preview and the issued loan, including its final payment. */
export function PaymentSchedule({ applicationId, revision, preliminary }: { applicationId: string; revision: string; preliminary: boolean }) {
  const result = useApi(() => applicationsApi.schedule(applicationId), [applicationId, revision]);
  const calculation = result.data;
  const rows = calculation?.schedule ?? [];
  const facts = calculation && rows.length ? [
    ['Первый платёж', rows[0].payment],
    ['Максимальный платёж', Math.max(...rows.map((row) => row.payment))],
    ['Последний платёж', rows[rows.length - 1].payment],
    ['Всего к возврату', calculation.totalPayment],
    ['Переплата', calculation.overpayment]
  ] as const : [];
  return (
    <Box sx={{ mt: 3 }}>
      <Typography variant="subtitle1" component="h3" fontWeight={600}>{preliminary ? 'Предварительный график платежей' : 'График платежей'}</Typography>
      {result.loading && !calculation && <LoadingBlock />}
      {result.error && <ErrorState message={result.error} onRetry={result.reload} />}
      {calculation && <>
        <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
          {preliminary ? 'Расчёт по условиям предложения до выдачи кредита. ' : 'Расчёт по сохранённым условиям выданного кредита. '}
          {calculation.revolving ? 'Для возобновляемого лимита показано полное использование: платежи меняются, остаток погашается в последний месяц.' : 'Последний платёж учитывает округление и закрывает остаток долга.'}
        </Typography>
        <Stack spacing={0.5} sx={{ my: 1.5 }}>
          {facts.map(([title, value]) => <Stack key={title} direction={{ xs: 'column', sm: 'row' }} justifyContent="space-between" spacing={{ xs: 0, sm: 2 }}>
            <Typography color="text.secondary">{title}</Typography><Typography sx={{ textAlign: { xs: 'left', sm: 'right' }, overflowWrap: 'anywhere' }}>{rubExact(value)}</Typography>
          </Stack>)}
        </Stack>
        <Box component="details">
          <Box component="summary" sx={{ cursor: 'pointer', minHeight: 44, py: 1, fontWeight: 600, '&:focus-visible': { outline: '2px solid', outlineColor: 'primary.main' } }}>Показать все платежи по месяцам</Box>
          <Typography variant="caption" color="text.secondary">Номер месяца расчёта; календарные даты платежей здесь не определены.</Typography>
          <TableScrollRegion label="Все столбцы графика платежей">
            <Table size="small" aria-label="График платежей">
              <TableHead><TableRow><TableCell>Месяц</TableCell><TableCell align="right">Платёж</TableCell><TableCell align="right">Основной долг</TableCell><TableCell align="right">Проценты</TableCell><TableCell align="right">Остаток</TableCell></TableRow></TableHead>
              <TableBody>{rows.map((row) => <TableRow key={row.month}><TableCell>{row.month}</TableCell><TableCell align="right">{rubExact(row.payment)}</TableCell><TableCell align="right">{rubExact(row.principal)}</TableCell><TableCell align="right">{rubExact(row.interest)}</TableCell><TableCell align="right">{rubExact(row.balance)}</TableCell></TableRow>)}</TableBody>
            </Table>
          </TableScrollRegion>
        </Box>
      </>}
    </Box>
  );
}
