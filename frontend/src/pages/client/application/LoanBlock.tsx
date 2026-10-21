import { Box, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { applicationsApi } from '../../../api/client';
import { ErrorState } from '../../../components/ErrorState';
import { LoadingBlock } from '../../../components/LoadingBlock';
import { useApi } from '../../../hooks/useApi';
import { formatDate, months, percent, rub, rubExact } from '../../../shared/format';
import { Application } from '../../../types';

/** The issued loan and its payment schedule. */
export function LoanBlock({ application }: { application: Application }) {
  const loan = application.process.loan!;
  const schedule = useApi(() => applicationsApi.schedule(application.id), [application.id]);
  const facts: Array<[string, string]> = [
    ['Договор', loan.contractNumber],
    ['Сумма', rub(loan.amount)],
    ['Срок', `${months(loan.termMonths)}, до ${formatDate(loan.maturityDate)}`],
    ['Ставка', percent(loan.rate)],
    ['Зачислено', formatDate(loan.disbursedAt)],
    ['Расчётный счёт', loan.settlementAccount],
    ['Ссудный счёт', loan.loanAccount]
  ];
  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 }, borderColor: 'success.main' }}>
      <Typography variant="h6" component="h2" gutterBottom>Кредит выдан</Typography>
      <Stack spacing={0.5}>
        {facts.map(([label, value]) => (
          <Stack key={label} direction="row" spacing={2}>
            <Typography color="text.secondary" sx={{ minWidth: 140 }}>{label}</Typography>
            <Typography sx={{ wordBreak: 'break-all' }}>{value}</Typography>
          </Stack>
        ))}
      </Stack>
      <Typography variant="subtitle1" fontWeight={600} sx={{ mt: 3, mb: 1 }}>График платежей</Typography>
      {schedule.loading && <LoadingBlock />}
      {schedule.error && <ErrorState message={schedule.error} onRetry={schedule.reload} />}
      {schedule.data && (
        <Box sx={{ overflowX: 'auto' }}>
          <Table size="small" aria-label="График платежей">
            <TableHead>
              <TableRow>
                <TableCell>Месяц</TableCell>
                <TableCell align="right">Платёж</TableCell>
                <TableCell align="right">Основной долг</TableCell>
                <TableCell align="right">Проценты</TableCell>
                <TableCell align="right">Остаток</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {schedule.data.schedule.map((row) => (
                <TableRow key={row.month}>
                  <TableCell>{row.month}</TableCell>
                  <TableCell align="right">{rubExact(row.payment)}</TableCell>
                  <TableCell align="right">{rubExact(row.principal)}</TableCell>
                  <TableCell align="right">{rubExact(row.interest)}</TableCell>
                  <TableCell align="right">{rubExact(row.balance)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Box>
      )}
    </Paper>
  );
}
