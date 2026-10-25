import { Alert, Box, Button, Dialog, DialogActions, DialogContent, DialogTitle, Paper, Stack, Table, TableBody, TableCell, TableHead, TableRow, TextField, Typography } from '@mui/material';
import { useState } from 'react';
import { applicationsApi } from '../../../api/client';
import { formatDateTime, months, percent, rub, rubExact } from '../../../shared/format';
import { Application } from '../../../types';
import { PaymentSchedule } from './PaymentSchedule';

/** The bank's terms next to the requested ones, so a counter offer is visible at a glance. */
export function OfferBlock({ application, onChanged, historical = false }: { application: Application; onChanged: (next: Application) => void; historical?: boolean }) {
  const offer = application.process.offer!;
  const [declining, setDeclining] = useState(false);
  const [comment, setComment] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const counter = offer.kind === 'COUNTER';
  const actionable = application.status === 'OFFER_ISSUED' && offer.status === 'ISSUED';

  const act = async (call: () => Promise<Application>) => {
    setBusy(true);
    setError(null);
    try {
      onChanged(await call());
      setDeclining(false);
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Не удалось выполнить действие');
    } finally {
      setBusy(false);
    }
  };

  const rows: Array<[string, string, string]> = [
    ['Продукт', application.product.name, offer.productName],
    ['Сумма', rub(application.amount), rub(offer.amount)],
    ['Срок', months(application.termMonths), months(offer.termMonths)],
    ['Ставка', application.estimate ? `от ${percent(application.estimate.rate)}` : '—', percent(offer.rate)],
    ['Первый платёж по графику', application.estimate ? rubExact(application.estimate.monthlyPayment) : '—', rubExact(offer.monthlyPayment)]
  ];

  return (
    <Paper variant="outlined" sx={{ p: { xs: 2, md: 3 }, borderColor: actionable ? 'success.main' : undefined }}>
      <Typography variant="h6" component="h2">{historical ? 'Принятое предложение' : counter ? 'Банк предлагает другие условия' : 'Заявка одобрена'}</Typography>
      {actionable && <Typography color="text.secondary">Предложение действует до {formatDateTime(offer.validUntil)}.</Typography>}
      {offer.changes.length > 0 && (
        <Alert severity="info" sx={{ mt: 2 }}>
          {offer.changes.map((change) => <Box key={change}>{change[0].toUpperCase() + change.slice(1)}</Box>)}
        </Alert>
      )}
      <Box sx={{ overflowX: 'auto', mt: 2 }}>
        <Table size="small" aria-label="Сравнение условий">
          <TableHead>
            <TableRow>
              <TableCell />
              <TableCell>Вы запросили</TableCell>
              <TableCell>{historical ? 'Согласовано с банком' : 'Предлагает банк'}</TableCell>
            </TableRow>
          </TableHead>
          <TableBody>
            {rows.map(([label, requested, offered]) => (
              <TableRow key={label}>
                <TableCell component="th" scope="row">{label}</TableCell>
                <TableCell>{requested}</TableCell>
                <TableCell sx={{ fontWeight: requested !== offered ? 700 : 400 }}>{offered}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Box>
      <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>Всего к возврату: {rubExact(offer.totalPayment)}</Typography>
      {!application.process.loan && <PaymentSchedule applicationId={application.id} revision={offer.id} preliminary />}
      {error && <Alert severity="error" sx={{ mt: 2 }}>{error}</Alert>}
      {actionable && (
        <Stack direction={{ xs: 'column', sm: 'row' }} spacing={1.5} sx={{ mt: 2 }}>
          <Button variant="contained" size="large" disabled={busy} onClick={() => act(() => applicationsApi.acceptOffer(application.id))}>Принять и перейти к подписанию</Button>
          <Button variant="outlined" size="large" disabled={busy} onClick={() => setDeclining(true)}>Отказаться</Button>
        </Stack>
      )}
      <Dialog open={declining} onClose={() => setDeclining(false)} fullWidth maxWidth="sm">
        <DialogTitle>Отказаться от предложения?</DialogTitle>
        <DialogContent>
          <Typography gutterBottom>Заявка закроется. Позже можно подать новую.</Typography>
          <TextField label="Почему не подошло (необязательно)" fullWidth multiline minRows={2} value={comment} onChange={(event) => setComment(event.target.value)} inputProps={{ maxLength: 2000 }} />
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeclining(false)}>Вернуться</Button>
          <Button color="error" disabled={busy} onClick={() => act(() => applicationsApi.declineOffer(application.id, comment.trim() || undefined))}>Отказаться</Button>
        </DialogActions>
      </Dialog>
    </Paper>
  );
}
