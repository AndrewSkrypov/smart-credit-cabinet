import { Alert, Box, Chip, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, Typography } from '@mui/material';
import { FinancingScenario } from '../api/financing';
import { formatDate, months, percent, rub } from '../shared/format';
import { TableScrollRegion } from './TableScrollRegion';

export function FinancingComparison({ scenario }: { scenario: FinancingScenario }) {
  const { inputs } = scenario;
  return (
    <Stack spacing={1.5}>
      <Typography variant="body2">
        {rub(scenario.loanAmount)} · {percent(scenario.annualRatePercent)} годовых · Платёж {formatDate(inputs.paymentDate)}
      </Typography>
      <Typography variant="body2" color="text.secondary">
        Остаток до поступления {rub(inputs.openingBalance)}. Ожидается {rub(inputs.expectedReceiptAmount)} к {formatDate(inputs.expectedReceiptDate)}. Задержка {inputs.delayDays} дн.
      </Typography>
      <TableScrollRegion label="Сравнение сценариев финансирования">
        <Table size="small" aria-label="Сравнение сценариев финансирования">
          <TableHead>
            <TableRow>
              <TableCell>Срок</TableCell>
              <TableCell align="right">Платёж в месяц</TableCell>
              <TableCell align="right">Остаток после платежа</TableCell>
              <TableCell align="right">Проценты за весь срок</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {scenario.options.map((option) => (
              <TableRow key={option.termMonths} selected={option.termMonths === inputs.selectedTermMonths}>
                <TableCell component="th" scope="row">
                  {months(option.termMonths)}
                  {option.termMonths === inputs.selectedTermMonths && <Chip size="small" label="Выбран" sx={{ ml: 1 }} />}
                </TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{rub(option.monthlyPayment)}</TableCell>
                <TableCell align="right" sx={{ color: option.balanceOnPaymentDate < 0 ? 'error.main' : 'text.primary', fontWeight: 600, whiteSpace: 'nowrap' }}>
                  {rub(option.balanceOnPaymentDate)}
                </TableCell>
                <TableCell align="right" sx={{ whiteSpace: 'nowrap' }}>{rub(option.totalInterest)}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableScrollRegion>
      {inputs.source && (
        <Box>
          <Typography variant="body2" fontWeight={600}>Подтверждённый источник · страница {inputs.source.page}</Typography>
          <Typography variant="body2" color="text.secondary" component="blockquote" sx={{ m: 0, overflowWrap: 'anywhere' }}>{inputs.source.amountQuote}</Typography>
          <Typography variant="body2" color="text.secondary" component="blockquote" sx={{ m: 0, overflowWrap: 'anywhere' }}>{inputs.source.dateQuote}</Typography>
        </Box>
      )}
    </Stack>
  );
}

export function FinancingScenarioView({ scenario }: { scenario: FinancingScenario }) {
  const label = scenario.status === 'CONFIRMED' ? 'Подтверждённый пакет' : 'Пакет на подтверждение';
  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 } }}>
      <Stack spacing={1.5}>
        <Typography variant="h6" component="h2">{label} · версия {scenario.revision}</Typography>
        {scenario.changes?.length ? (
          <Alert severity="info">
            Изменения после предыдущей передачи
            <Box component="ul" sx={{ my: 0, pl: 2 }}>
              {scenario.changes.map((change, index) => <li key={index}>{change}</li>)}
            </Box>
          </Alert>
        ) : null}
        <FinancingComparison scenario={scenario} />
      </Stack>
    </Paper>
  );
}
