import { Paper, Stack, Typography } from '@mui/material';
import { formatDate, months, percent, rub } from '../../../shared/format';
import { Application } from '../../../types';
import { PaymentSchedule } from './PaymentSchedule';

/** The issued loan and its payment schedule. */
export function LoanBlock({ application }: { application: Application }) {
  const loan = application.process.loan!;
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
      <PaymentSchedule applicationId={application.id} revision={loan.contractNumber} preliminary={false} />
    </Paper>
  );
}
